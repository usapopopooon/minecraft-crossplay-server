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
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Consumer<Runnable> schedule;
    private final Prompt prompt;
    private final LongSupplier clock;
    private final SourceArea sourceArea;

    interface Prompt {
        boolean open(Player player, String label, Runnable confirm, Runnable cancel);
    }

    interface SourceArea {
        // Returns the registered gate containing this position, or null for other travel.
        String portalAt(Location location);
    }

    WorldTravelConfirmation(Consumer<Runnable> schedule, Prompt prompt,
                            LongSupplier clock, SourceArea sourceArea) {
        this.schedule = schedule;
        this.prompt = prompt;
        this.clock = clock;
        this.sourceArea = sourceArea;
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
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                player.setPortalCooldown(Math.max(player.getPortalCooldown(), 10));
            }
            return;
        }
        event.setCancelled(true);
        if (existing != null && existing.player == player && stillAtSource(existing)) {
            return; // No repeat dialogs while standing in the same gate, even after Cancel.
        }
        Pending request = new Pending(player, event.getFrom().clone(), event.getTo().clone(),
                event.getCause(), sourceArea.portalAt(event.getFrom()), clock.getAsLong());
        pending.put(player.getUniqueId(), request);
        schedule.accept(() -> {
            if (!current(request) || !valid(request)) {
                cancel(request);
                return;
            }
            String label = group(request.to.getWorld()) == Group.SECOND
                    ? "world_2" : "world_1 側（ネザー・エンドを含む）";
            if (!prompt.open(player, label, () -> confirm(request), () -> cancel(request))) {
                cancel(request);
                player.sendMessage("確認画面を開けなかったため、移動を中止しました。");
            }
        });
    }

    private void confirm(Pending request) {
        if (!current(request) || request.resolved) {
            return;
        }
        request.resolved = true;
        if (!valid(request)) {
            request.player.sendMessage("確認の期限切れ、または位置が変わったため移動を中止しました。");
            return;
        }
        request.approved = true;
        // Do not snapshot or restore items here: MVI must use the CURRENT inventory,
        // including legitimate pickups/trades made while the dialog was open.
        try {
            CompletableFuture<Boolean> result = request.player.teleportAsync(request.to.clone(), request.cause);
            result.whenComplete((success, error) -> schedule.accept(() -> {
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
            request.approved = false;
            request.player.sendMessage("移動できませんでした。ゲートから離れて、もう一度お試しください。");
        }
    }

    private void cancel(Pending request) {
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
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { clear(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { clear(event.getPlayer()); }

    private void clear(Player player) {
        Pending request = pending.get(player.getUniqueId());
        if (request != null && request.player == player) {
            pending.remove(player.getUniqueId(), request);
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
