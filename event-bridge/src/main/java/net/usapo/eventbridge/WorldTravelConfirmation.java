package net.usapo.eventbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Confirms a resolved teleport before MVI switches inventories on world change. */
final class WorldTravelConfirmation implements Listener {
    private static final long TIMEOUT_MILLIS = 30_000;
    private static final Consumer<String> NO_DIAGNOSTICS = ignored -> {};
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Consumer<Runnable> schedule;
    private final Prompt prompt;
    private final LongSupplier clock;
    private final SourceArea sourceArea;
    private final Consumer<String> diagnostics;

    interface Prompt {
        boolean open(Player player, String label, Runnable confirm, Runnable cancel);
    }

    interface SourceArea {
        // Returns the registered gate containing this position, or null for other travel.
        String portalAt(Location location);
    }

    WorldTravelConfirmation(Consumer<Runnable> schedule, Prompt prompt,
                            LongSupplier clock, SourceArea sourceArea) {
        this(schedule, prompt, clock, sourceArea, NO_DIAGNOSTICS);
    }

    WorldTravelConfirmation(Consumer<Runnable> schedule, Prompt prompt,
                            LongSupplier clock, SourceArea sourceArea, Consumer<String> diagnostics) {
        this.schedule = schedule;
        this.prompt = prompt;
        this.clock = clock;
        this.sourceArea = sourceArea;
        this.diagnostics = diagnostics;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // PlayerPortalEvent#getTo is a preliminary search position, NOT the actual exit.
        // Paper fires a separate PlayerTeleportEvent after finding the actual portal exit.
        if (event instanceof PlayerPortalEvent || event.isCancelled() || event.getTo() == null) {
            return;
        }
        if (!changesInventoryGroup(event.getFrom().getWorld(), event.getTo().getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        Pending existing = pending.get(player.getUniqueId());
        if (existing != null && existing.player == player && existing.approved
                && !existing.consumed && valid(existing)
                && sameLocation(event.getTo(), existing.to)) {
            existing.consumed = true;
            trace(existing, "replay-allowed");
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                player.setPortalCooldown(Math.max(player.getPortalCooldown(), 10));
            }
            return;
        }
        event.setCancelled(true);
        if (existing != null && existing.player == player && suppressRepeat(existing)) {
            trace(existing, "request-suppressed");
            return;
        }
        Pending request = new Pending(player, event.getFrom().clone(), event.getTo().clone(),
                event.getCause(), sourceArea.portalAt(event.getFrom()), clock.getAsLong());
        pending.put(player.getUniqueId(), request);
        trace(request, "request-new");
        schedule.accept(() -> {
            if (!current(request) || !valid(request)) {
                trace(request, "next-tick-invalid");
                cancel(request, "next-tick-invalid");
                return;
            }
            String label = group(request.to.getWorld()) == Group.SECOND
                    ? "world_2" : "world_1 側（ネザー・エンドを含む）";
            boolean opened = prompt.open(player, label, () -> confirm(request), () -> cancel(request, "callback"));
            trace(request, "prompt opened=" + opened);
            if (!opened) {
                cancel(request, "prompt-unavailable");
                player.sendMessage("確認画面を開けなかったため、移動を中止しました。");
            }
        });
    }

    /** Observes earlier cancellation too, without changing the event or pending state. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observeTeleport(PlayerTeleportEvent event) {
        if (diagnostics == NO_DIAGNOSTICS || event instanceof PlayerPortalEvent || event.getTo() == null
                || !changesInventoryGroup(event.getFrom().getWorld(), event.getTo().getWorld())) {
            return;
        }
        try {
            Player player = event.getPlayer();
            diagnostics.accept("world-travel teleport-monitor player=" + player.getUniqueId()
                    + " cause=" + event.getCause() + " cancelled=" + event.isCancelled()
                    + " pendingPresent=" + pending.containsKey(player.getUniqueId())
                    + " online=" + player.isOnline() + " dead=" + player.isDead()
                    + " sleeping=" + player.isSleeping() + " passengersCount=" + player.getPassengers().size()
                    + " insideVehicle=" + player.isInsideVehicle());
        } catch (RuntimeException ignored) {
            // Diagnostics must remain independent of teleport processing.
        }
    }

    private void confirm(Pending request) {
        if (!current(request) || request.resolved) {
            trace(request, "confirm-ignored");
            return;
        }
        request.resolved = true;
        boolean valid = valid(request);
        trace(request, "confirm valid=" + valid);
        if (!valid) {
            request.player.sendMessage("確認の期限切れ、または位置が変わったため移動を中止しました。");
            return;
        }
        request.approved = true;
        // Do not snapshot or restore items here: MVI must use the CURRENT inventory,
        // including legitimate pickups/trades made while the dialog was open.
        try {
            CompletableFuture<Boolean> result = request.player.teleportAsync(request.to.clone(), request.cause);
            result.whenComplete((success, error) -> schedule.accept(() -> {
                trace(request, "replay-result success=" + success + " error="
                        + (error == null ? "none" : error.getClass().getSimpleName()));
                if (!current(request)) {
                    return;
                }
                request.approved = false;
                if (error != null || !Boolean.TRUE.equals(success)) {
                    request.player.sendMessage("移動できませんでした。ゲートから離れて、もう一度お試しください。");
                } else {
                    pending.remove(request.player.getUniqueId(), request);
                }
            }));
        } catch (RuntimeException error) {
            trace(request, "replay-threw error=" + error.getClass().getSimpleName());
            request.approved = false;
            request.player.sendMessage("移動できませんでした。ゲートから離れて、もう一度お試しください。");
        }
    }

    private void cancel(Pending request, String reason) {
        trace(request, "cancel reason=" + reason);
        if (current(request) && !request.resolved) {
            request.resolved = true;
            request.approved = false;
        }
    }

    private boolean current(Pending request) {
        return pending.get(request.player.getUniqueId()) == request;
    }

    private boolean valid(Pending request) {
        return request.player.isOnline() && !request.player.isDead()
                && clock.getAsLong() - request.created <= TIMEOUT_MILLIS && stillAtSource(request);
    }

    private boolean suppressRepeat(Pending request) {
        if (!stillAtSource(request)) {
            return false;
        }
        // Gate movement is automatic: Cancel must stay quiet until the player exits.
        // Outside a gate, a later command is an explicit retry after cancellation or expiry.
        // Preserve a live prompt or its in-flight approved replay instead of replacing it.
        return request.portal != null || (clock.getAsLong() - request.created <= TIMEOUT_MILLIS
                && (!request.resolved || request.approved));
    }

    /** Diagnostic observations only; no location coordinates, item data, or player names. */
    private void trace(Pending request, String phase) {
        if (diagnostics == NO_DIAGNOSTICS) {
            return;
        }
        try {
            Location location = request.player.getLocation();
            boolean sameWorld = location != null && location.getWorld() == request.from.getWorld();
            String source = request.portal != null
                    ? "gate sameGate=" + (sameWorld && request.portal.equals(sourceArea.portalAt(location)))
                    : "distance distanceSquared=" + (sameWorld ? location.distanceSquared(request.from) : "unavailable");
            diagnostics.accept("world-travel " + phase + " player=" + request.player.getUniqueId()
                    + " cause=" + request.cause + " current=" + current(request)
                    + " online=" + request.player.isOnline() + " dead=" + request.player.isDead()
                    + " ageMs=" + (clock.getAsLong() - request.created) + " sameWorld=" + sameWorld
                    + " source=" + source + " resolved=" + request.resolved
                    + " approved=" + request.approved + " consumed=" + request.consumed);
        } catch (RuntimeException ignored) {
            // A diagnostic sink must never change travel behavior.
        }
    }

    private boolean stillAtSource(Pending request) {
        return stillAtSource(request, request.player.getLocation());
    }

    private boolean stillAtSource(Pending request, Location current) {
        if (current == null) {
            return false;
        }
        if (current.getWorld() != request.from.getWorld()) {
            return false;
        }
        if (request.portal != null) {
            return request.portal.equals(sourceArea.portalAt(current));
        }
        return current.distanceSquared(request.from) <= 4;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Pending request = pending.get(event.getPlayer().getUniqueId());
        if (request != null && request.player == event.getPlayer() && !request.approved
                && !stillAtSource(request, event.getTo())) {
            pending.remove(event.getPlayer().getUniqueId(), request);
            trace(request, "clear reason=move");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer(), "quit"); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { clear(event.getPlayer(), "death"); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { clear(event.getPlayer(), "world-change"); }

    private void clear(Player player, String reason) {
        Pending request = pending.get(player.getUniqueId());
        if (request != null && request.player == player) {
            pending.remove(player.getUniqueId(), request);
            trace(request, "clear reason=" + reason);
        }
    }

    static boolean changesInventoryGroup(World from, World to) {
        return from != null && to != null && group(from) != group(to);
    }

    private static Group group(World world) {
        return world.getKey().toString().equals("minecraft:resource") ? Group.SECOND : Group.MAIN;
    }

    private static boolean sameLocation(Location first, Location second) {
        return first.getWorld() == second.getWorld() && first.distanceSquared(second) < 0.000001;
    }

    private enum Group { MAIN, SECOND }

    private static final class Pending {
        final Player player;
        final Location from;
        final Location to;
        final PlayerTeleportEvent.TeleportCause cause;
        final String portal;
        final long created;
        boolean resolved;
        boolean approved;
        boolean consumed;

        Pending(Player player, Location from, Location to, PlayerTeleportEvent.TeleportCause cause,
                String portal, long created) {
            this.player = player;
            this.from = from;
            this.to = to;
            this.cause = cause;
            this.portal = portal;
            this.created = created;
        }
    }
}
