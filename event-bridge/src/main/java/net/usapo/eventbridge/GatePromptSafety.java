package net.usapo.eventbridge;

import java.util.List;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

/** Read-only, loaded-chunk checks for opening a prompt outside a purple gate surface. */
final class GatePromptSafety implements WorldTravelConfirmation.GateSafety {
    private static final int MAX_PORTALS = 64;
    private static final int MAX_NATIVE_PORTAL_HEIGHT = 21;
    private static final double EPSILON = 0.0000001;
    private static final Gate UNSAFE_NATIVE_GATE = new Gate(null, false);
    private final PortalManager portals;
    private final boolean includeNativePortals;

    GatePromptSafety(PortalManager portals) {
        this(portals, false);
    }

    GatePromptSafety(PortalManager portals, boolean includeNativePortals) {
        this.portals = portals;
        this.includeNativePortals = includeNativePortals;
    }

    @Override
    public boolean touchingGate(Player player, Location at) {
        return touching(player, at) != null;
    }

    @Override
    public Location safeOutside(Player player, Location from) {
        Gate gate = touching(player, from);
        if (gate == null || gate.bounds() == null) {
            return null;
        }
        var bounds = gate.bounds();
        boolean xPlane = gate.xPlane();
        // Leave enough clearance for a held movement key before the prompt is rendered.
        double first = xPlane ? bounds.minX() - 1.5 : bounds.minZ() - 1.5;
        double second = xPlane ? bounds.maxX() + 2.5 : bounds.maxZ() + 2.5;
        double current = xPlane ? from.getX() : from.getZ();
        if (Math.abs(second - current) < Math.abs(first - current)) {
            double swap = first;
            first = second;
            second = swap;
        }
        // Preserve the current height first; the gate's bottom gives a grounded fallback.
        double[] heights = from.getY() == bounds.minY()
                ? new double[] {from.getY()} : new double[] {from.getY(), bounds.minY()};
        for (double height : heights) {
            for (double side : new double[] {first, second}) {
                Location candidate = from.clone();
                candidate.setY(height);
                if (xPlane) candidate.setX(side);
                else candidate.setZ(side);
                if (safeStandingSpace(player, candidate) && !touchingGate(player, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Gate touching(Player player, Location at) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        World world = at.getWorld();
        boolean normalWorld = world.getEnvironment() == World.Environment.NORMAL;
        if (!normalWorld && !(includeNativePortals && world.getEnvironment() == World.Environment.NETHER)) {
            return null;
        }
        BoundingBox body = bodyAt(player, at);
        if (body == null) {
            return null;
        }
        // Keep registered normal-world gates authoritative, including their full bottom edge.
        List<MVPortal> registered = normalWorld ? portals.getAllPortals() : List.of();
        for (int i = 0; i < Math.min(registered.size(), MAX_PORTALS); i++) {
            MVPortal portal = registered.get(i);
            if (portal.getBukkitWorld().getOrNull() != at.getWorld()) {
                continue;
            }
            PortalLocation selection = portal.getPortalLocation();
            if (selection == null || !selection.isValidLocation()) {
                continue;
            }
            var bounds = MultiversePortalEffects.bounds(selection.getMinimum(), selection.getMaximum());
            if (bounds == null) {
                continue;
            }
            BoundingBox volume = new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
            if (body.overlaps(volume) && containsPurple(at.getWorld(), body.clone().intersection(volume))) {
                return new Gate(bounds, bounds.minX() == bounds.maxX());
            }
        }
        return includeNativePortals ? nativeTouching(world, body) : null;
    }

    private static Gate nativeTouching(World world, BoundingBox body) {
        if (body.getMinY() < world.getMinHeight() || body.getMaxY() > world.getMaxHeight()
                || !loaded(world, body)) {
            return null;
        }
        // bodyAt caps every dimension at four blocks, so this scan is bounded to 125 cells.
        for (int x = floor(body.getMinX()); x <= lastBlock(body.getMaxX()); x++) {
            for (int y = floor(body.getMinY()); y <= lastBlock(body.getMaxY()); y++) {
                for (int z = floor(body.getMinZ()); z <= lastBlock(body.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.NETHER_PORTAL) {
                        continue;
                    }
                    Axis axis = portalAxis(block);
                    if (axis == null) {
                        return UNSAFE_NATIVE_GATE;
                    }
                    int bottom = y;
                    for (int depth = 1; depth <= MAX_NATIVE_PORTAL_HEIGHT; depth++) {
                        int belowY = y - depth;
                        if (belowY < world.getMinHeight()) {
                            return UNSAFE_NATIVE_GATE;
                        }
                        Block below = world.getBlockAt(x, belowY, z);
                        if (below.getType() != Material.NETHER_PORTAL) {
                            // Axis describes the in-plane direction, not the exit direction.
                            return new Gate(new MultiversePortalEffects.Bounds(x, bottom, z, x, y, z),
                                    axis == Axis.Z);
                        }
                        if (portalAxis(below) != axis) {
                            return UNSAFE_NATIVE_GATE;
                        }
                        bottom = belowY;
                    }
                    // Contact is still real: an unsupported/tall surface must not open a prompt in place.
                    return UNSAFE_NATIVE_GATE;
                }
            }
        }
        return null;
    }

    private static Axis portalAxis(Block block) {
        if (!(block.getBlockData() instanceof Orientable orientable)) {
            return null;
        }
        Axis axis = orientable.getAxis();
        return axis == Axis.X || axis == Axis.Z ? axis : null;
    }

    private static boolean containsPurple(World world, BoundingBox intersection) {
        if (intersection.getMinY() < world.getMinHeight() || intersection.getMaxY() > world.getMaxHeight()
                || !loaded(world, intersection)) {
            return false;
        }
        for (int x = floor(intersection.getMinX()); x <= lastBlock(intersection.getMaxX()); x++) {
            for (int y = floor(intersection.getMinY()); y <= lastBlock(intersection.getMaxY()); y++) {
                for (int z = floor(intersection.getMinZ()); z <= lastBlock(intersection.getMaxZ()); z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.NETHER_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean safeStandingSpace(Player player, Location candidate) {
        BoundingBox body = bodyAt(player, candidate);
        if (body == null) {
            return false;
        }
        // A crouching/swimming player must also have room to stand after the prompt opens.
        body.resize(body.getMinX(), body.getMinY(), body.getMinZ(), body.getMaxX(),
                Math.max(body.getMaxY(), body.getMinY() + 1.8), body.getMaxZ());
        World world = candidate.getWorld();
        int floorY = floor(body.getMinY()) - 1;
        if (floorY < world.getMinHeight() || body.getMaxY() > world.getMaxHeight() || !loaded(world, body)) {
            return false;
        }
        for (int x = floor(body.getMinX()); x <= lastBlock(body.getMaxX()); x++) {
            for (int z = floor(body.getMinZ()); z <= lastBlock(body.getMaxZ()); z++) {
                Block support = world.getBlockAt(x, floorY, z);
                if (!safeSupport(support, body, x, floorY, z)) {
                    return false;
                }
                for (int y = floor(body.getMinY()); y <= lastBlock(body.getMaxY()); y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean safeSupport(Block block, BoundingBox body, int x, int y, int z) {
        if (!block.isSolid() || block.isLiquid() || hazardous(block.getType())
                || (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())) {
            return false;
        }
        BoundingBox support = block.getBoundingBox();
        return support != null && Math.abs(support.getMaxY() - (y + 1)) <= EPSILON
                && support.getMinX() <= Math.max(body.getMinX(), x) + EPSILON
                && support.getMaxX() >= Math.min(body.getMaxX(), x + 1) - EPSILON
                && support.getMinZ() <= Math.max(body.getMinZ(), z) + EPSILON
                && support.getMaxZ() >= Math.min(body.getMaxZ(), z + 1) - EPSILON;
    }

    private static boolean hazardous(Material type) {
        return switch (type) {
            case MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, FIRE, SOUL_FIRE,
                    LAVA, WATER, BUBBLE_COLUMN, POWDER_SNOW, SWEET_BERRY_BUSH,
                    WITHER_ROSE, POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }

    private static BoundingBox bodyAt(Player player, Location at) {
        Location current = player.getLocation();
        BoundingBox body = player.getBoundingBox();
        if (current == null || body == null || current.getWorld() != at.getWorld()
                || !finite(at.getX()) || !finite(at.getY()) || !finite(at.getZ())
                || !finite(current.getX()) || !finite(current.getY()) || !finite(current.getZ())
                || body.getWidthX() <= 0 || body.getWidthX() > 4 || body.getWidthZ() <= 0
                || body.getWidthZ() > 4 || body.getHeight() <= 0 || body.getHeight() > 4
                || !finite(body.getMinX()) || !finite(body.getMinY()) || !finite(body.getMinZ())
                || !finite(body.getMaxX()) || !finite(body.getMaxY()) || !finite(body.getMaxZ())) {
            return null;
        }
        return body.clone().shift(at.getX() - current.getX(), at.getY() - current.getY(), at.getZ() - current.getZ());
    }

    private static boolean loaded(World world, BoundingBox box) {
        for (int x = floor(box.getMinX()) >> 4; x <= lastBlock(box.getMaxX()) >> 4; x++) {
            for (int z = floor(box.getMinZ()) >> 4; z <= lastBlock(box.getMaxZ()) >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value) && Math.abs(value) < 30_000_000;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int lastBlock(double exclusiveMax) {
        return floor(Math.nextDown(exclusiveMax));
    }

    private record Gate(MultiversePortalEffects.Bounds bounds, boolean xPlane) {}
}
