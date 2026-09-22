package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalFiller;
import org.mvplugins.multiverse.portals.utils.PortalManager;

/** Initializes registered gates with the standard animated purple portal surface. */
final class MultiversePortalEffects implements Runnable {
    static final int MAX_PORTALS_PER_RUN = 64;
    static final int MAX_BLOCKS_PER_RUN = 1024;
    static final int MAX_GATE_SIZE = 32;

    private final PortalManager portals;
    private final PortalFiller filler;
    private final Set<MVPortal> initialized = Collections.newSetFromMap(new IdentityHashMap<>());
    private int portalOffset;

    MultiversePortalEffects(PortalManager portals, PortalFiller filler) {
        this.portals = portals;
        this.filler = filler;
    }

    /** Run on the server thread. A manually extinguished gate is not filled again. */
    @Override
    public void run() {
        List<MVPortal> registered = portals.getAllPortals();
        initialized.retainAll(registered);
        if (registered.isEmpty()) {
            return;
        }
        int start = Math.floorMod(portalOffset, registered.size());
        int remaining = MAX_BLOCKS_PER_RUN;
        for (int i = 0; i < Math.min(registered.size(), MAX_PORTALS_PER_RUN); i++) {
            MVPortal portal = registered.get((start + i) % registered.size());
            if (initialized.contains(portal)) {
                continue;
            }
            PortalLocation selection = portal.getPortalLocation();
            if (selection == null || !selection.isValidLocation()) {
                continue;
            }
            Bounds bounds = bounds(selection.getMinimum(), selection.getMaximum());
            if (bounds == null || bounds.area() > remaining) {
                continue;
            }
            World world = portal.getBukkitWorld().getOrNull();
            if (world == null || world.getEnvironment() != World.Environment.NORMAL
                    || bounds.minY() <= world.getMinHeight() || bounds.maxY() >= world.getMaxHeight() - 1
                    || !loadedWithFillHalo(world, bounds)) {
                continue;
            }
            remaining -= bounds.area();
            List<Block> air = airBlocks(world, bounds);
            if (air == null) {
                continue;
            }
            // PortalFiller preserves solid frames, sets the correct axis, and suppresses physics.
            // Scan every open component: its recursive fill can stop at selection boundaries.
            for (Block block : air) {
                if (block.getType() == Material.AIR) {
                    filler.fillRegion(selection.getRegion(), block.getLocation(), Material.NETHER_PORTAL);
                }
            }
            initialized.add(portal);
        }
        portalOffset = (start + 1) % registered.size();
    }

    private static List<Block> airBlocks(World world, Bounds bounds) {
        List<Block> air = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.AIR) {
                        air.add(block);
                    } else if (type != Material.NETHER_PORTAL
                            && (MVPortal.isPortalInterior(type)
                                    || type == Material.CAVE_AIR || type == Material.VOID_AIR)) {
                        // The standard filler replaces liquids/vines/snow. Leave such gates untouched.
                        return null;
                    }
                }
            }
        }
        return air;
    }

    private static boolean loadedWithFillHalo(World world, Bounds bounds) {
        // PortalFiller inspects a neighbor before checking region membership. Check that halo too.
        int dx = bounds.minX() == bounds.maxX() ? 0 : 1;
        int dz = bounds.minZ() == bounds.maxZ() ? 0 : 1;
        for (int x = (bounds.minX() - dx) >> 4; x <= (bounds.maxX() + dx) >> 4; x++) {
            for (int z = (bounds.minZ() - dz) >> 4; z <= (bounds.maxZ() + dz) >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    static Bounds bounds(Vector first, Vector second) {
        if (first == null || second == null || !finite(first) || !finite(second)) {
            return null;
        }
        Vector min = Vector.getMinimum(first, second);
        Vector max = Vector.getMaximum(first, second);
        long width = (long) max.getBlockX() - min.getBlockX() + 1;
        long height = (long) max.getBlockY() - min.getBlockY() + 1;
        long depth = (long) max.getBlockZ() - min.getBlockZ() + 1;
        boolean vertical = (depth == 1 && width >= 2) || (width == 1 && depth >= 2);
        if (!vertical || height < 2 || height > MAX_GATE_SIZE
                || width > MAX_GATE_SIZE || depth > MAX_GATE_SIZE) {
            return null;
        }
        return new Bounds(min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                max.getBlockX(), max.getBlockY(), max.getBlockZ());
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.getX()) && Math.abs(vector.getX()) < 30_000_000
                && Double.isFinite(vector.getY()) && Math.abs(vector.getY()) < 30_000_000
                && Double.isFinite(vector.getZ()) && Math.abs(vector.getZ()) < 30_000_000;
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int area() {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }
}
