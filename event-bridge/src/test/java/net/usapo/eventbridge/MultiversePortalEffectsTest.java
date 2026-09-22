package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.external.vavr.control.Option;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalFiller;
import org.mvplugins.multiverse.portals.utils.PortalManager;

final class MultiversePortalEffectsTest {
    @Test
    void verticalSelectionsRetainBothOrientationsAndReversedNegativeCorners() {
        assertEquals(20, MultiversePortalEffects.bounds(new Vector(0, 64, 0), new Vector(3, 68, 0)).area());
        assertEquals(new MultiversePortalEffects.Bounds(-4, 64, -4, -4, 68, -1),
                MultiversePortalEffects.bounds(new Vector(-4, 68, -1), new Vector(-4, 64, -4)));
        assertNotNull(MultiversePortalEffects.bounds(new Vector(), new Vector(31, 31, 0)));
    }

    @Test
    void thickHorizontalSingleColumnOversizedAndInvalidRegionsAreSkipped() {
        for (Vector max : List.of(new Vector(3, 4, 1), new Vector(3, 0, 3), new Vector(0, 4, 0),
                new Vector(32, 4, 0), new Vector(3, 32, 0), new Vector(Double.NaN, 4, 0),
                new Vector(3, Double.POSITIVE_INFINITY, 0), new Vector(Double.MAX_VALUE, 4, 0))) {
            assertNull(MultiversePortalEffects.bounds(new Vector(), max), max.toString());
        }
        assertNull(MultiversePortalEffects.bounds(null, new Vector()));
    }

    @Test
    void standardPurpleFillUsesOnlyRegisteredAirAndPreservesFramesAndDestination() {
        Fixture fixture = new Fixture();
        when(fixture.block(0, 64, 0).getType()).thenReturn(Material.GLOWSTONE);
        when(fixture.block(3, 68, 0).getType()).thenReturn(Material.OBSIDIAN);
        fixture.effects.run();
        assertEquals(18, fills(fixture.filler));
        assertEquals(Material.GLOWSTONE, fixture.block(0, 64, 0).getType());
        assertEquals(Material.OBSIDIAN, fixture.block(3, 68, 0).getType());
        assertTrue(mockingDetails(fixture.filler).getInvocations().stream().allMatch(invocation ->
                invocation.getArgument(0) == fixture.portal.getPortalLocation().getRegion()
                        && invocation.getArgument(2) == Material.NETHER_PORTAL));
        assertTrue(mockingDetails(fixture.portal).getInvocations().stream()
                .noneMatch(invocation -> invocation.getMethod().getName().startsWith("set")));
        verify(fixture.world, never()).getPlayers();
    }

    @Test
    void manualExtinguishingDoesNotTriggerContinualRefilling() {
        Fixture fixture = new Fixture();
        fixture.effects.run();
        assertEquals(20, fills(fixture.filler));
        when(fixture.block(1, 66, 0).getType()).thenReturn(Material.AIR);
        fixture.effects.run();
        fixture.effects.run();
        assertEquals(20, fills(fixture.filler));
        assertEquals(Material.AIR, fixture.block(1, 66, 0).getType());
    }

    @Test
    void emptyRegistryAndLaterCreatedOrRecreatedGatesAreHandled() {
        Fixture fixture = new Fixture();
        when(fixture.manager.getAllPortals()).thenReturn(List.of());
        fixture.effects.run();
        verifyNoInteractions(fixture.filler);
        when(fixture.manager.getAllPortals()).thenReturn(List.of(fixture.portal));
        fixture.effects.run();
        assertEquals(20, fills(fixture.filler));
        MVPortal replacement = portal(fixture.world, new Vector(5, 64, 0), new Vector(8, 68, 0));
        when(fixture.manager.getAllPortals()).thenReturn(List.of(replacement));
        fixture.effects.run();
        assertEquals(40, fills(fixture.filler));
    }

    @Test
    void unloadedWorldOrChunkIncludingTheFillHaloWaitsWithoutLoadingAnything() {
        Fixture fixture = new Fixture();
        when(fixture.portal.getBukkitWorld()).thenReturn(Option.none());
        fixture.effects.run();
        verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        when(fixture.portal.getBukkitWorld()).thenReturn(Option.of(fixture.world));
        // The region starts at x=0; the standard filler also reads the neighbor at x=-1.
        when(fixture.world.isChunkLoaded(-1, 0)).thenReturn(false);
        fixture.effects.run();
        verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(fixture.filler);
        when(fixture.world.isChunkLoaded(-1, 0)).thenReturn(true);
        fixture.effects.run();
        assertEquals(20, fills(fixture.filler));
        verify(fixture.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void netherEndAndBuildHeightEdgesAreUntouched() {
        for (World.Environment environment : List.of(World.Environment.NETHER, World.Environment.THE_END)) {
            Fixture fixture = new Fixture();
            when(fixture.world.getEnvironment()).thenReturn(environment);
            fixture.effects.run();
            verifyNoInteractions(fixture.filler);
            verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
        for (int minY : List.of(-64, 318)) {
            Fixture fixture = new Fixture();
            MVPortal edgePortal = portal(fixture.world, new Vector(0, minY, 0), new Vector(3, minY + 1, 0));
            when(fixture.manager.getAllPortals()).thenReturn(List.of(edgePortal));
            fixture.effects.run();
            verifyNoInteractions(fixture.filler);
        }
    }

    @Test
    void liquidsDecorationsAndUnsupportedAirPreventAllChangesToTheGate() {
        for (Material material : List.of(Material.WATER, Material.LAVA, Material.VINE,
                Material.SNOW, Material.CAVE_AIR, Material.VOID_AIR)) {
            Fixture fixture = new Fixture();
            when(fixture.block(3, 68, 0).getType()).thenReturn(material);
            fixture.effects.run();
            verifyNoInteractions(fixture.filler);
            assertEquals(Material.AIR, fixture.block(0, 64, 0).getType());
        }
    }

    @Test
    void existingPortalSurfaceIsNotReplacedAndInvalidLocationIsNotResolved() {
        Fixture fixture = new Fixture();
        when(fixture.block(0, 64, 0).getType()).thenReturn(Material.NETHER_PORTAL);
        fixture.effects.run();
        assertEquals(19, fills(fixture.filler));
        Fixture invalid = new Fixture();
        when(invalid.portal.getPortalLocation()).thenReturn(new PortalLocation());
        invalid.effects.run();
        verify(invalid.portal, never()).getBukkitWorld();
        verifyNoInteractions(invalid.filler);
    }

    @Test
    void regionBudgetIsSharedAndLargeGatesTakeTurns() {
        Fixture fixture = new Fixture();
        MVPortal first = portal(fixture.world, new Vector(0, 64, 0), new Vector(31, 95, 0));
        MVPortal second = portal(fixture.world, new Vector(32, 64, 0), new Vector(63, 95, 0));
        when(fixture.manager.getAllPortals()).thenReturn(List.of(first, second));
        fixture.effects.run();
        assertEquals(MultiversePortalEffects.MAX_BLOCKS_PER_RUN, fills(fixture.filler));
        fixture.effects.run();
        assertEquals(2L * MultiversePortalEffects.MAX_BLOCKS_PER_RUN, fills(fixture.filler));
    }

    @Test
    void portalScanIsBoundedEvenWhenNoLocationIsValid() {
        Fixture fixture = new Fixture();
        List<MVPortal> portals = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            MVPortal portal = mock(MVPortal.class);
            when(portal.getPortalLocation()).thenReturn(new PortalLocation());
            portals.add(portal);
        }
        when(fixture.manager.getAllPortals()).thenReturn(portals);
        fixture.effects.run();
        long reads = portals.stream().flatMap(portal -> mockingDetails(portal).getInvocations().stream())
                .filter(invocation -> invocation.getMethod().getName().equals("getPortalLocation")).count();
        assertEquals(MultiversePortalEffects.MAX_PORTALS_PER_RUN, reads);
    }

    private static long fills(PortalFiller filler) {
        return mockingDetails(filler).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("fillRegion")).count();
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
        final PortalManager manager = mock(PortalManager.class);
        final PortalFiller filler = mock(PortalFiller.class);
        final MVPortal portal = portal(world, new Vector(0, 64, 0), new Vector(3, 68, 0));
        final MultiversePortalEffects effects = new MultiversePortalEffects(manager, filler);
        final Map<Vector, Block> blocks = new HashMap<>();

        Fixture() {
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                    block(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            when(manager.getAllPortals()).thenReturn(List.of(portal));
            doAnswer(invocation -> {
                Location seed = invocation.getArgument(1);
                when(block(seed.getBlockX(), seed.getBlockY(), seed.getBlockZ()).getType())
                        .thenReturn(Material.NETHER_PORTAL);
                return true;
            }).when(filler).fillRegion(any(), any(Location.class), eq(Material.NETHER_PORTAL));
        }

        Block block(int x, int y, int z) {
            return blocks.computeIfAbsent(new Vector(x, y, z), key -> {
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(Material.AIR);
                when(block.getLocation()).thenReturn(new Location(world, x, y, z));
                return block;
            });
        }
    }
}
