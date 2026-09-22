package net.usapo.eventbridge;

import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

/** Client-only surfaces over real air. Never creates blocks, changes physics, or loads chunks. */
final class CyanGateOverlay implements Runnable, Listener, AutoCloseable {
    static final Set<String> GATE_NAMES = Set.of("to_world_1", "to_world_2");
    static final String X_PLANE_STATE = "minecraft:tripwire[attached=true,disarmed=true,east=true,"
            + "north=true,powered=false,south=true,west=true]";
    static final String Z_PLANE_STATE = "minecraft:tripwire[attached=true,disarmed=true,east=true,"
            + "north=true,powered=true,south=true,west=true]";
    static final int MAX_PORTALS_PER_RUN = 64;
    static final int MAX_VIEWERS_PER_RUN = 32;
    static final int MAX_UPDATES_PER_PLAYER = 128;
    static final int MAX_UPDATES_PER_RUN = 2048;
    static final double RANGE_SQUARED = 48 * 48;
    private static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(70, 220, 255), 1.1f);

    private final PortalManager portals;
    private final Supplier<? extends Collection<? extends Player>> onlinePlayers;
    private final Predicate<Player> packReady;
    private final BlockData xPlane;
    private final BlockData zPlane;
    private final Map<UUID, View> views = new HashMap<>();
    private int viewerOffset;
    private boolean closed;

    CyanGateOverlay(PortalManager portals, Supplier<? extends Collection<? extends Player>> onlinePlayers,
                    Predicate<Player> packReady, BlockData xPlane, BlockData zPlane) {
        this.portals = portals;
        this.onlinePlayers = onlinePlayers;
        this.packReady = packReady;
        this.xPlane = xPlane;
        this.zPlane = zPlane;
    }

    /** Main-thread polling also repairs client block updates and applies a newly accepted pack. */
    @Override
    public void run() {
        if (closed) return;
        List<? extends Player> players = new ArrayList<>(onlinePlayers.get());
        views.values().removeIf(view -> !view.player.isOnline() || !players.contains(view.player));
        if (players.isEmpty()) return;
        List<Surface> surfaces = surfaces(players);
        int start = Math.floorMod(viewerOffset, players.size());
        int remaining = MAX_UPDATES_PER_RUN;
        for (int i = 0; i < Math.min(players.size(), MAX_VIEWERS_PER_RUN); i++) {
            Player player = players.get((start + i) % players.size());
            if (!player.isOnline()) continue;
            View view = views.compute(player.getUniqueId(), (id, previous) ->
                    previous != null && previous.player == player ? previous : new View(player));
            Map<Cell, BlockData> wanted = new LinkedHashMap<>();
            boolean ready = packReady.test(player);
            for (Surface surface : surfaces) {
                if (!near(player, surface.world, surface.bounds)) continue;
                for (Map.Entry<Cell, BlockData> entry : surface.cells.entrySet()) {
                    if (sent(player, entry.getKey())) wanted.put(entry.getKey(), entry.getValue());
                }
                if (!ready && !surface.cells.isEmpty() && sent(player, surface.cells.keySet().iterator().next())) {
                    fallback(player, surface);
                }
            }
            if (!ready) wanted.clear();
            int budget = Math.min(MAX_UPDATES_PER_PLAYER, remaining);
            int used = render(view, wanted, budget);
            remaining -= used;
        }
        // Rotate even when the shared packet budget runs out, so busy gates cannot starve a viewer.
        viewerOffset = (start + 1) % players.size();
    }

    private List<Surface> surfaces(List<? extends Player> players) {
        List<Surface> result = new ArrayList<>();
        Set<String> found = new java.util.HashSet<>();
        List<MVPortal> registered = portals.getAllPortals();
        // The allowlist bounds block reads to two 32 x 32 surfaces, independent of registry size.
        for (int i = 0; i < Math.min(registered.size(), MAX_PORTALS_PER_RUN); i++) {
            MVPortal portal = registered.get(i);
            if (!GATE_NAMES.contains(portal.getName()) || !found.add(portal.getName())) continue;
            PortalLocation selection = portal.getPortalLocation();
            if (selection == null || !selection.isValidLocation()) continue;
            var bounds = MultiversePortalEffects.bounds(selection.getMinimum(), selection.getMaximum());
            World world = portal.getBukkitWorld().getOrNull();
            if (bounds == null || world == null || world.getEnvironment() != World.Environment.NORMAL
                    || bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()
                    || players.stream().noneMatch(player -> near(player, world, bounds))
                    || !loaded(world, bounds)) continue;
            Map<Cell, BlockData> cells = new LinkedHashMap<>();
            BlockData visual = bounds.minZ() == bounds.maxZ() ? xPlane : zPlane;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        // Frames, liquids, decorations, real tripwires and native portals remain real.
                        if (world.getBlockAt(x, y, z).getType() == Material.AIR) {
                            cells.put(new Cell(world, x, y, z), visual);
                        }
                    }
                }
            }
            result.add(new Surface(world, bounds, cells));
        }
        return result;
    }

    private static boolean loaded(World world, MultiversePortalEffects.Bounds bounds) {
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    private static boolean near(Player player, World world, MultiversePortalEffects.Bounds bounds) {
        Location at = player.getLocation();
        if (at == null || at.getWorld() != world || !Double.isFinite(at.getX())
                || !Double.isFinite(at.getY()) || !Double.isFinite(at.getZ())) return false;
        double x = Math.max(bounds.minX(), Math.min(at.getX(), bounds.maxX() + 1));
        double y = Math.max(bounds.minY(), Math.min(at.getY(), bounds.maxY() + 1));
        double z = Math.max(bounds.minZ(), Math.min(at.getZ(), bounds.maxZ() + 1));
        return at.distanceSquared(new Location(world, x, y, z)) <= RANGE_SQUARED;
    }

    private static int render(View view, Map<Cell, BlockData> wanted, int budget) {
        int used = 0;
        var iterator = view.shown.entrySet().iterator();
        while (iterator.hasNext()) {
            Cell cell = iterator.next().getKey();
            if (!sent(view.player, cell)) {
                iterator.remove(); // A dimension/chunk unload already removed the client-side illusion.
            } else if (!wanted.containsKey(cell) && used < budget) {
                restore(view.player, cell);
                used++;
                iterator.remove();
            }
        }
        if (used == budget || wanted.isEmpty()) return used;
        List<Map.Entry<Cell, BlockData>> entries = new ArrayList<>(wanted.entrySet());
        int start = Math.floorMod(view.cursor, entries.size());
        int processed = 0;
        for (; processed < entries.size() && used < budget; processed++) {
            var entry = entries.get((start + processed) % entries.size());
            Cell cell = entry.getKey();
            view.player.sendBlockChange(cell.location(), entry.getValue());
            view.shown.put(cell, entry.getValue());
            used++;
        }
        view.cursor = (start + processed) % entries.size();
        return used;
    }

    private static void fallback(Player player, Surface surface) {
        var b = surface.bounds;
        double x = (b.minX() + b.maxX() + 1) / 2.0;
        double y = (b.minY() + b.maxY() + 1) / 2.0;
        double z = (b.minZ() + b.maxZ() + 1) / 2.0;
        player.spawnParticle(Particle.DUST, x, y, z, 12,
                (b.maxX() - b.minX()) / 2.0, (b.maxY() - b.minY()) / 2.0,
                (b.maxZ() - b.minZ()) / 2.0, 0, CYAN);
    }

    private static boolean sent(Player player, Cell cell) {
        return player.getWorld() == cell.world && cell.world.isChunkLoaded(cell.x >> 4, cell.z >> 4)
                && player.isChunkSent(chunkKey(cell.x >> 4, cell.z >> 4));
    }

    static long chunkKey(int x, int z) {
        return ((long) z << 32) | (x & 0xffffffffL);
    }

    private static void restore(Player player, Cell cell) {
        // Restore the CURRENT block, not a snapshot that could hide a player's later build.
        player.sendBlockChange(cell.location(), cell.world.getBlockAt(cell.x, cell.y, cell.z).getBlockData());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { views.remove(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { views.remove(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onChunkUnload(PlayerChunkUnloadEvent event) {
        View view = views.get(event.getPlayer().getUniqueId());
        if (view != null) view.shown.keySet().removeIf(cell -> cell.world == event.getWorld()
                && (cell.x >> 4) == event.getChunk().getX() && (cell.z >> 4) == event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (!event.isCancelled()) views.values().forEach(view ->
                view.shown.keySet().removeIf(cell -> cell.world == event.getWorld()));
    }

    @Override
    public void close() {
        closed = true;
        for (View view : views.values()) {
            if (!view.player.isOnline()) continue;
            // Batched restoration bounds packet count by chunk sections during plugin disable.
            Map<io.papermc.paper.math.BlockPosition, BlockData> real = new HashMap<>();
            for (Cell cell : view.shown.keySet()) {
                if (sent(view.player, cell)) real.put(io.papermc.paper.math.Position.block(cell.x, cell.y, cell.z),
                        cell.world.getBlockAt(cell.x, cell.y, cell.z).getBlockData());
            }
            if (!real.isEmpty()) view.player.sendMultiBlockChange(real);
        }
        views.clear();
    }

    private record Cell(World world, int x, int y, int z) {
        Location location() { return new Location(world, x, y, z); }
    }

    private record Surface(World world, MultiversePortalEffects.Bounds bounds, Map<Cell, BlockData> cells) {}

    private static final class View {
        final Player player;
        final Map<Cell, BlockData> shown = new LinkedHashMap<>();
        int cursor;
        View(Player player) { this.player = player; }
    }
}
