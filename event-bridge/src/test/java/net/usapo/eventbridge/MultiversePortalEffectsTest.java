package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.external.vavr.control.Option;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

final class MultiversePortalEffectsTest {
    @Test
    void verticalSelectionsProduceBoundedAquaPlanesInEitherOrientation() {
        List<Vector> alongX = MultiversePortalEffects.samplePoints(
                new Vector(0, 64, 0), new Vector(3, 68, 0), 0);
        assertEquals(MultiversePortalEffects.POINTS_PER_PORTAL, alongX.size());
        for (Vector point : alongX) {
            assertTrue(point.getX() >= 0.2 && point.getX() < 3.8);
            assertTrue(point.getY() >= 64.2 && point.getY() < 68.8);
            assertTrue(point.getZ() >= 0.44 && point.getZ() <= 0.56);
        }
        // Reversed, negative corners still give a correctly centered one-block plane.
        List<Vector> alongZ = MultiversePortalEffects.samplePoints(
                new Vector(-4, 68, -1), new Vector(-4, 64, -4), 7);
        assertEquals(MultiversePortalEffects.POINTS_PER_PORTAL, alongZ.size());
        for (Vector point : alongZ) {
            assertTrue(point.getX() >= -3.56 && point.getX() <= -3.44);
            assertTrue(point.getZ() >= -3.8 && point.getZ() < -0.2);
        }
        assertNotEquals(alongX, MultiversePortalEffects.samplePoints(
                new Vector(0, 64, 0), new Vector(3, 68, 0), 1));
        assertEquals(80, MultiversePortalEffects.AQUA.getColor().getRed());
        assertEquals(210, MultiversePortalEffects.AQUA.getColor().getGreen());
        assertEquals(255, MultiversePortalEffects.AQUA.getColor().getBlue());
    }

    @Test
    void thickHorizontalSingleColumnOversizedAndInvalidRegionsAreSkipped() {
        Vector origin = new Vector(0, 0, 0);
        for (Vector max : List.of(
                new Vector(3, 4, 1), new Vector(3, 0, 3), new Vector(0, 4, 0),
                new Vector(32, 4, 0), new Vector(3, 32, 0),
                new Vector(Double.NaN, 4, 0), new Vector(3, Double.POSITIVE_INFINITY, 0))) {
            assertTrue(MultiversePortalEffects.samplePoints(origin, max, 0).isEmpty(), max.toString());
        }
        assertTrue(MultiversePortalEffects.samplePoints(null, origin, 0).isEmpty());
        assertFalse(MultiversePortalEffects.samplePoints(origin, new Vector(31, 31, 0), 0).isEmpty());
    }

    @Test
    void emptyRegistryDoesNothingAndLaterRegisteredGatesArePickedUp() {
        Fixture fixture = new Fixture();
        when(fixture.manager.getAllPortals()).thenReturn(List.of());
        fixture.effects.run();
        assertEquals(0, sends(fixture.player));

        when(fixture.manager.getAllPortals()).thenReturn(List.of(fixture.portal));
        fixture.effects.run();
        assertEquals(MultiversePortalEffects.POINTS_PER_PORTAL, sends(fixture.player));
        assertTrue(mockingDetails(fixture.player).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("spawnParticle"))
                .allMatch(invocation -> invocation.getArgument(0) == Particle.DUST
                        && invocation.<Integer>getArgument(2) == 1
                        && invocation.getArgument(7) == MultiversePortalEffects.AQUA));
    }

    @Test
    void absentFarAwayOrOfflineViewersNeverCauseBlockReadsOrParticles() {
        for (String scenario : List.of("none", "far", "offline", "other-world")) {
            Fixture fixture = new Fixture();
            switch (scenario) {
                case "none" -> when(fixture.world.getPlayers()).thenReturn(List.of());
                case "far" -> when(fixture.player.getLocation())
                        .thenReturn(new Location(fixture.world, 100, 66, 0));
                case "offline" -> when(fixture.player.isOnline()).thenReturn(false);
                case "other-world" -> when(fixture.player.getLocation())
                        .thenReturn(new Location(mock(World.class), 1, 66, 0));
                default -> throw new AssertionError(scenario);
            }
            fixture.effects.run();
            assertEquals(0, sends(fixture.player), scenario);
            verify(fixture.world, never()).isChunkLoaded(anyInt(), anyInt());
            verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void unloadedWorldAndOtherDimensionsNeverRender() {
        Fixture unloaded = new Fixture();
        when(unloaded.portal.getBukkitWorld()).thenReturn(Option.none());
        unloaded.effects.run();
        assertEquals(0, sends(unloaded.player));
        verify(unloaded.world, never()).getPlayers();

        for (World.Environment environment : List.of(World.Environment.NETHER, World.Environment.THE_END)) {
            Fixture fixture = new Fixture();
            when(fixture.world.getEnvironment()).thenReturn(environment);
            fixture.effects.run();
            assertEquals(0, sends(fixture.player));
            verify(fixture.world, never()).getPlayers();
        }
    }

    @Test
    void unloadedChunksAreNotReadAndSolidFrameBlocksAreNotRendered() {
        Fixture unloaded = new Fixture();
        when(unloaded.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        unloaded.effects.run();
        assertEquals(0, sends(unloaded.player));
        verify(unloaded.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());

        Fixture solid = new Fixture();
        when(solid.block.isPassable()).thenReturn(false);
        solid.effects.run();
        assertEquals(0, sends(solid.player));
    }

    @Test
    void invalidPortalLocationDoesNotTryToResolveItsWorld() {
        Fixture fixture = new Fixture();
        when(fixture.portal.getPortalLocation()).thenReturn(new PortalLocation());
        fixture.effects.run();
        verify(fixture.portal, never()).getBukkitWorld();
        assertEquals(0, sends(fixture.player));
    }

    @Test
    void budgetIsSharedAcrossGatesAndViewersAndFirstGateRotates() {
        Fixture fixture = new Fixture();
        List<Player> viewers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            viewers.add(viewer(fixture.world, 8, 66, 0));
        }
        when(fixture.world.getPlayers()).thenReturn(viewers);
        MVPortal second = portal(fixture.world, new Vector(16, 64, 0), new Vector(19, 68, 0));
        when(fixture.manager.getAllPortals()).thenReturn(List.of(fixture.portal, second));

        fixture.effects.run();
        assertEquals(MultiversePortalEffects.MAX_PARTICLE_SENDS,
                viewers.stream().mapToLong(MultiversePortalEffectsTest::sends).sum());
        fixture.effects.run();
        assertEquals(2L * MultiversePortalEffects.MAX_PARTICLE_SENDS,
                viewers.stream().mapToLong(MultiversePortalEffectsTest::sends).sum());
        List<Location> locations = viewers.stream()
                .flatMap(player -> mockingDetails(player).getInvocations().stream())
                .filter(invocation -> invocation.getMethod().getName().equals("spawnParticle"))
                .map(invocation -> invocation.<Location>getArgument(1))
                .toList();
        assertTrue(locations.stream().anyMatch(location -> location.getX() < 4));
        assertTrue(locations.stream().anyMatch(location -> location.getX() > 16));
    }

    @Test
    void portalScanIsBoundedEvenWhenNoWorldIsLoaded() {
        PortalManager manager = mock(PortalManager.class);
        List<MVPortal> portals = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            MVPortal portal = mock(MVPortal.class);
            when(portal.getPortalLocation()).thenReturn(new PortalLocation());
            portals.add(portal);
        }
        when(manager.getAllPortals()).thenReturn(portals);
        new MultiversePortalEffects(manager).run();
        long reads = portals.stream().flatMap(portal -> mockingDetails(portal).getInvocations().stream())
                .filter(invocation -> invocation.getMethod().getName().equals("getPortalLocation"))
                .count();
        assertEquals(MultiversePortalEffects.MAX_PORTALS_PER_RUN, reads);
    }

    private static long sends(Player player) {
        return mockingDetails(player).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("spawnParticle"))
                .count();
    }

    private static Player viewer(World world, double x, double y, double z) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static MVPortal portal(World world, Vector min, Vector max) {
        MVPortal portal = mock(MVPortal.class);
        PortalLocation selection = new PortalLocation();
        selection.setLocation(min, max, mock(MultiverseWorld.class));
        when(portal.getPortalLocation()).thenReturn(selection);
        when(portal.getBukkitWorld()).thenReturn(Option.of(world));
        return portal;
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Player player = viewer(world, 1, 66, 0);
        final MVPortal portal = portal(world, new Vector(0, 64, 0), new Vector(3, 68, 0));
        final PortalManager manager = mock(PortalManager.class);
        final MultiversePortalEffects effects = new MultiversePortalEffects(manager);

        Fixture() {
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getPlayers()).thenReturn(List.of(player));
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(block.isPassable()).thenReturn(true);
            when(manager.getAllPortals()).thenReturn(List.of(portal));
        }
    }
}
