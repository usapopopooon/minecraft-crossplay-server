package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

/** A small, client-only aqua shimmer for registered, hollow Multiverse gates. */
final class MultiversePortalEffects implements Runnable {
    static final int MAX_PARTICLE_SENDS = 128;
    static final int POINTS_PER_PORTAL = 12;
    static final int MAX_PORTALS_PER_RUN = 64;
    static final int MAX_VIEWERS = 20;
    static final double VIEW_DISTANCE = 32;
    static final int MAX_GATE_SIZE = 32;
    static final Particle.DustOptions AQUA =
            new Particle.DustOptions(Color.fromRGB(80, 210, 255), 1.1f);

    private final PortalManager portals;
    private long phase;
    private int portalOffset;

    MultiversePortalEffects(PortalManager portals) {
        this.portals = portals;
    }

    /** Must run on the server thread; neither worlds nor chunks are loaded here. */
    @Override
    public void run() {
        List<MVPortal> registered = portals.getAllPortals();
        if (registered.isEmpty()) {
            return;
        }
        int remaining = MAX_PARTICLE_SENDS;
        int start = Math.floorMod(portalOffset, registered.size());
        long currentPhase = phase++;
        for (int i = 0; i < Math.min(registered.size(), MAX_PORTALS_PER_RUN) && remaining > 0; i++) {
            MVPortal portal = registered.get((start + i) % registered.size());
            PortalLocation selection = portal.getPortalLocation();
            if (selection == null || !selection.isValidLocation()) {
                continue;
            }
            World world = portal.getBukkitWorld().getOrNull();
            if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            List<Vector> points = samplePoints(selection.getMinimum(), selection.getMaximum(), currentPhase);
            if (points.isEmpty()) {
                continue;
            }
            Vector center = selection.getMinimum().clone().add(selection.getMaximum())
                    .add(new Vector(1, 1, 1)).multiply(0.5);
            List<Player> viewers = nearbyViewers(world, center);
            if (viewers.isEmpty()) {
                continue;
            }
            for (Vector point : points) {
                if (!world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)) {
                    continue;
                }
                // A wand selection may include the frame. Draw only in its open interior.
                if (!world.getBlockAt(point.getBlockX(), point.getBlockY(), point.getBlockZ()).isPassable()) {
                    continue;
                }
                Location location = point.toLocation(world);
                for (int j = 0; j < viewers.size() && remaining > 0; j++) {
                    Player viewer = viewers.get((j + (int) Math.floorMod(currentPhase, viewers.size()))
                            % viewers.size());
                    viewer.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, AQUA);
                    remaining--;
                }
                if (remaining == 0) {
                    break;
                }
            }
        }
        // Rotate the first gate so a crowded gate cannot starve other nearby gates.
        portalOffset = (start + 1) % registered.size();
    }

    private static List<Player> nearbyViewers(World world, Vector center) {
        List<Player> viewers = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            if (player.isOnline() && location.getWorld() == world
                    && location.toVector().distanceSquared(center) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                viewers.add(player);
                if (viewers.size() == MAX_VIEWERS) {
                    break;
                }
            }
        }
        return viewers;
    }

    /** Samples a vertical, one-block-thick selection without traversing its volume. */
    static List<Vector> samplePoints(Vector first, Vector second, long phase) {
        if (first == null || second == null || !finite(first) || !finite(second)) {
            return List.of();
        }
        Vector min = Vector.getMinimum(first, second);
        Vector max = Vector.getMaximum(first, second);
        double width = (double) max.getBlockX() - min.getBlockX() + 1;
        double height = (double) max.getBlockY() - min.getBlockY() + 1;
        double depth = (double) max.getBlockZ() - min.getBlockZ() + 1;
        boolean alongX = depth == 1 && width >= 2;
        boolean alongZ = width == 1 && depth >= 2;
        if ((!alongX && !alongZ) || height < 2 || height > MAX_GATE_SIZE
                || width > MAX_GATE_SIZE || depth > MAX_GATE_SIZE) {
            return List.of();
        }
        List<Vector> points = new ArrayList<>(POINTS_PER_PORTAL);
        double animation = Math.floorMod(phase, 10_000) * 0.137;
        for (int i = 0; i < POINTS_PER_PORTAL; i++) {
            double across = fraction(i * 0.61803398875 + animation);
            double up = fraction(i * 0.41421356237 + animation * 0.7);
            double ripple = Math.sin((i + animation) * 1.7) * 0.06;
            double x = min.getBlockX() + (alongX ? 0.2 + across * (width - 0.4) : 0.5 + ripple);
            double z = min.getBlockZ() + (alongZ ? 0.2 + across * (depth - 0.4) : 0.5 + ripple);
            double y = min.getBlockY() + 0.2 + up * (height - 0.4);
            points.add(new Vector(x, y, z));
        }
        return List.copyOf(points);
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }
}
