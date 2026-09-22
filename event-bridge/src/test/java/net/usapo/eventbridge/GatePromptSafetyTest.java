package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.external.vavr.control.Option;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

final class GatePromptSafetyTest {
    @Test
    void bodyContactIsDetectedWhenFeetAreOutsideAndExactNonOverlapIsNotContact() {
        Fixture f = new Fixture();
        assertTrue(f.safety.touchingGate(f.player, new Location(f.world, 416.8, 56, 112.5)));
        assertFalse(f.safety.touchingGate(f.player, new Location(f.world, 416.7, 56, 112.5)));
        assertFalse(f.safety.touchingGate(f.player, new Location(f.world, 416.5, 56, 112.5)));
        assertTrue(f.safety.touchingGate(f.player, new Location(f.world, 417.5, 56, 111.8)));
        assertFalse(f.safety.touchingGate(f.player, new Location(f.world, 417.5, 56, 111.7)));
        assertFalse(f.safety.touchingGate(f.player, new Location(f.world, 417.5, 59, 112.5)));
    }

    @Test
    void safeCandidateUsesWestWhenEastIsWalledAndPreservesAlongCoordinateAndView() {
        Fixture f = new Fixture();
        f.current = new Location(f.world, 417.8, 56, 112.6, 73, -12);
        f.type(419, 56, 112, Material.SMOOTH_SANDSTONE);
        Location result = f.safety.safeOutside(f.player, f.current);
        assertNotNull(result);
        assertEquals(new Location(f.world, 415.5, 56, 112.6, 73, -12), result);
        assertFalse(f.safety.touchingGate(f.player, result));
        assertEquals(417.8, f.current.getX());
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void prefersNearestSafeSideAndUsesGateBottomWhenCurrentHeightHasNoFloor() {
        Fixture f = new Fixture();
        f.current = new Location(f.world, 417.8, 57, 112.5);
        Location result = f.safety.safeOutside(f.player, f.current);
        assertEquals(new Location(f.world, 419.5, 56, 112.5), result);
    }

    @Test
    void negativeCoordinatesAndZPlaneUseCorrectOppositeAxis() {
        Fixture f = new Fixture();
        f.min = new Vector(-25, 68, -17);
        f.max = new Vector(-24, 70, -17);
        f.floorY = 67;
        f.current = new Location(f.world, -24.5, 68, -16.5, 20, 8);
        f.register();
        assertTrue(f.safety.touchingGate(f.player, new Location(f.world, -24.5, 68, -17.2)));
        assertEquals(new Location(f.world, -24.5, 68, -18.5, 20, 8), f.safety.safeOutside(f.player, f.current));
    }

    @Test
    void absentOrHollowOrOtherWorldOrInvalidGatesNeverYieldAnExit() {
        for (String scenario : List.of("empty", "hollow", "other-world", "unloaded-world", "invalid", "nether")) {
            Fixture f = new Fixture();
            switch (scenario) {
                case "empty" -> when(f.manager.getAllPortals()).thenReturn(List.of());
                case "hollow" -> f.purple = false;
                case "other-world" -> when(f.portal.getBukkitWorld()).thenReturn(Option.of(mock(World.class)));
                case "unloaded-world" -> when(f.portal.getBukkitWorld()).thenReturn(Option.none());
                case "invalid" -> when(f.portal.getPortalLocation()).thenReturn(new PortalLocation());
                case "nether" -> when(f.world.getEnvironment()).thenReturn(World.Environment.NETHER);
                default -> throw new AssertionError(scenario);
            }
            assertFalse(f.safety.touchingGate(f.player, f.current), scenario);
            assertNull(f.safety.safeOutside(f.player, f.current), scenario);
        }
    }

    @Test
    void unloadedSourceChunkIsNotReadAndUnloadedExitChunkIsNotLoaded() {
        Fixture source = new Fixture();
        when(source.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertNull(source.safety.safeOutside(source.player, source.current));
        verify(source.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());

        Fixture exit = new Fixture();
        exit.min = new Vector(0, 56, 0);
        exit.max = new Vector(0, 58, 1);
        exit.current = new Location(exit.world, 0.5, 56, 0.5);
        exit.register();
        when(exit.world.isChunkLoaded(-1, 0)).thenReturn(false);
        exit.type(2, 56, 0, Material.STONE);
        assertTrue(exit.safety.touchingGate(exit.player, exit.current));
        assertNull(exit.safety.safeOutside(exit.player, exit.current));
        verify(exit.world, never()).getBlockAt(-2, 55, 0);
        verify(exit.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void hazardousLiquidWaterloggedAndIncompleteFloorsAreRejected() {
        for (String scenario : List.of("magma", "cactus", "campfire", "water", "waterlogged", "slab", "narrow", "missing")) {
            Fixture f = new Fixture();
            for (int x : List.of(415, 419)) {
                Block support = f.block(x, 55, 112);
                switch (scenario) {
                    case "magma" -> when(support.getType()).thenReturn(Material.MAGMA_BLOCK);
                    case "cactus" -> when(support.getType()).thenReturn(Material.CACTUS);
                    case "campfire" -> when(support.getType()).thenReturn(Material.CAMPFIRE);
                    case "water" -> when(support.isLiquid()).thenReturn(true);
                    case "waterlogged" -> {
                        Waterlogged data = mock(Waterlogged.class);
                        when(data.isWaterlogged()).thenReturn(true);
                        when(support.getBlockData()).thenReturn(data);
                    }
                    case "slab" -> when(support.getBoundingBox()).thenReturn(new BoundingBox(x, 55, 112, x + 1, 55.5, 113));
                    case "narrow" -> when(support.getBoundingBox()).thenReturn(new BoundingBox(x + 0.4, 55, 112, x + 0.6, 56, 113));
                    case "missing" -> when(support.isSolid()).thenReturn(false);
                    default -> throw new AssertionError(scenario);
                }
            }
            assertNull(f.safety.safeOutside(f.player, f.current), scenario);
        }
    }

    @Test
    void headObstructionsLiquidsAndOtherPortalSurfacesAreRejected() {
        for (Material material : List.of(Material.STONE, Material.WATER, Material.LAVA, Material.NETHER_PORTAL, Material.FIRE)) {
            Fixture f = new Fixture();
            f.type(415, 57, 112, material);
            f.type(419, 57, 112, material);
            assertNull(f.safety.safeOutside(f.player, f.current), material.toString());
        }
    }

    @Test
    void crouchingStillRequiresStandingHeadroomAndFootprintNeedsSupportAcrossBlockBoundary() {
        Fixture crouching = new Fixture();
        crouching.height = 0.6;
        crouching.type(415, 57, 112, Material.STONE);
        crouching.type(419, 57, 112, Material.STONE);
        assertNull(crouching.safety.safeOutside(crouching.player, crouching.current));
        Fixture boundary = new Fixture();
        boundary.current.setZ(113.0);
        when(boundary.block(415, 55, 113).isSolid()).thenReturn(false);
        when(boundary.block(419, 55, 113).isSolid()).thenReturn(false);
        assertNull(boundary.safety.safeOutside(boundary.player, boundary.current));
    }

    @Test
    void readsOnlyTheFirst64RegisteredPortalsAndDoesNotMutateBlocks() {
        Fixture f = new Fixture();
        List<MVPortal> all = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            MVPortal portal = mock(MVPortal.class);
            when(portal.getBukkitWorld()).thenReturn(Option.none());
            all.add(portal);
        }
        all.add(f.portal);
        when(f.manager.getAllPortals()).thenReturn(all);
        assertFalse(f.safety.touchingGate(f.player, f.current));
        verify(f.portal, never()).getBukkitWorld();
        verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        f.register();
        assertNotNull(f.safety.safeOutside(f.player, f.current));
        assertTrue(f.blocks.values().stream().flatMap(block -> mockingDetails(block).getInvocations().stream())
                .noneMatch(call -> call.getMethod().getName().startsWith("set")));
    }

    @Test
    void nativeAxisZWorksInNormalAndNetherWorldsButRemainsOptIn() {
        for (World.Environment environment : List.of(World.Environment.NORMAL, World.Environment.NETHER)) {
            Fixture f = new Fixture();
            when(f.world.getEnvironment()).thenReturn(environment);
            f.current = new Location(f.world, 417.8, 56, 112.6, 73, -12);
            GatePromptSafety safety = f.nativeSafety(Axis.Z);
            f.type(419, 56, 112, Material.STONE);
            assertFalse(f.safety.touchingGate(f.player, f.current));
            assertTrue(safety.touchingGate(f.player, f.current));
            assertEquals(new Location(f.world, 415.5, 56, 112.6, 73, -12), safety.safeOutside(f.player, f.current));
            verify(f.player, never()).getInventory();
            verify(f.player, never()).getEnderChest();
            verify(f.world, never()).getChunkAt(anyInt(), anyInt());
            assertTrue(f.blocks.values().stream().flatMap(block -> mockingDetails(block).getInvocations().stream())
                    .noneMatch(call -> call.getMethod().getName().startsWith("set")));
        }
    }

    @Test
    void nativeAxisXAtNegativeCoordinatesMovesAlongZNotX() {
        for (World.Environment environment : List.of(World.Environment.NORMAL, World.Environment.NETHER)) {
            Fixture f = new Fixture();
            when(f.world.getEnvironment()).thenReturn(environment);
            f.min = new Vector(-25, 68, -17);
            f.max = new Vector(-24, 70, -17);
            f.floorY = 67;
            f.current = new Location(f.world, -24.5, 68, -16.5, 20, 8);
            GatePromptSafety safety = f.nativeSafety(Axis.X);
            Location result = safety.safeOutside(f.player, f.current);
            assertEquals(new Location(f.world, -24.5, 68, -18.5, 20, 8), result);
            assertFalse(safety.touchingGate(f.player, result));
        }
    }

    @Test
    void nativeContactUsesBodyOverlapIncludingFeetOutsideButExcludesExactBoundaries() {
        Fixture f = new Fixture();
        GatePromptSafety safety = f.nativeSafety(Axis.Z);
        assertTrue(safety.touchingGate(f.player, new Location(f.world, 416.8, 56, 112.5)));
        assertFalse(safety.touchingGate(f.player, new Location(f.world, 416.7, 56, 112.5)));
        assertTrue(safety.touchingGate(f.player, new Location(f.world, 417.5, 56, 111.8)));
        assertFalse(safety.touchingGate(f.player, new Location(f.world, 417.5, 56, 111.7)));
        assertFalse(safety.touchingGate(f.player, new Location(f.world, 417.5, 59, 112.5)));

        Fixture negative = new Fixture();
        negative.min = new Vector(-25, 68, -17);
        negative.max = new Vector(-24, 70, -17);
        negative.floorY = 67;
        negative.current = new Location(negative.world, -24.5, 68, -16.5);
        GatePromptSafety negativeSafety = negative.nativeSafety(Axis.X);
        assertTrue(negativeSafety.touchingGate(negative.player, new Location(negative.world, -24.5, 68, -17.2)));
        assertFalse(negativeSafety.touchingGate(negative.player, new Location(negative.world, -24.5, 68, -17.3)));
    }

    @Test
    void nativeBottomScanSupportsTwentyOneHighButFailsClosedBeyondItsLimit() {
        for (int height : List.of(21, 22)) {
            Fixture f = new Fixture();
            f.max.setY(f.min.getY() + height - 1);
            f.current.setY(f.max.getY());
            GatePromptSafety safety = f.nativeSafety(Axis.Z);
            assertTrue(safety.touchingGate(f.player, f.current));
            if (height == 21) {
                assertEquals(new Location(f.world, 415.5, 56, 112.5), safety.safeOutside(f.player, f.current));
            } else {
                assertNull(safety.safeOutside(f.player, f.current));
                verify(f.world, never()).getBlockAt(417, 55, 112);
            }
        }
    }

    @Test
    void malformedNativeAxisOrAxisDiscontinuityRetainsContactButNeverReturnsAnExit() {
        for (String scenario : List.of("missing", "vertical", "discontinuous", "world-bottom")) {
            Fixture f = new Fixture();
            f.current.setY(57);
            GatePromptSafety safety = f.nativeSafety(scenario.equals("missing") ? null : Axis.Z);
            if (scenario.equals("vertical")) f.nativeAxis = Axis.Y;
            if (scenario.equals("discontinuous")) {
                Orientable wrongAxis = mock(Orientable.class);
                when(wrongAxis.getAxis()).thenReturn(Axis.X);
                when(f.block(417, 56, 112).getBlockData()).thenReturn(wrongAxis);
            }
            if (scenario.equals("world-bottom")) when(f.world.getMinHeight()).thenReturn(56);
            assertTrue(safety.touchingGate(f.player, f.current), scenario);
            assertNull(safety.safeOutside(f.player, f.current), scenario);
        }
    }

    @Test
    void nativeModeRejectsNonPortalBlocksAndEndWorlds() {
        for (String scenario : List.of("air", "stone", "end-portal", "end-world")) {
            Fixture f = new Fixture();
            GatePromptSafety safety = f.nativeSafety(Axis.Z);
            if (scenario.equals("end-world")) {
                when(f.world.getEnvironment()).thenReturn(World.Environment.THE_END);
            } else {
                f.purple = false;
                Material material = switch (scenario) {
                    case "stone" -> Material.OBSIDIAN;
                    case "end-portal" -> Material.END_PORTAL;
                    default -> Material.AIR;
                };
                f.type(417, 56, 112, material);
            }
            assertFalse(safety.touchingGate(f.player, f.current), scenario);
            assertNull(safety.safeOutside(f.player, f.current), scenario);
        }
    }

    @Test
    void nativeExitsRejectHazardsMissingFloorsAndObstructedHeadroom() {
        for (String scenario : List.of("magma", "missing-floor", "headroom", "liquid")) {
            Fixture f = new Fixture();
            GatePromptSafety safety = f.nativeSafety(Axis.Z);
            for (int x : List.of(415, 419)) {
                switch (scenario) {
                    case "magma" -> f.type(x, 55, 112, Material.MAGMA_BLOCK);
                    case "missing-floor" -> when(f.block(x, 55, 112).isSolid()).thenReturn(false);
                    case "headroom" -> f.type(x, 57, 112, Material.STONE);
                    case "liquid" -> f.type(x, 56, 112, Material.WATER);
                    default -> throw new AssertionError(scenario);
                }
            }
            assertTrue(safety.touchingGate(f.player, f.current), scenario);
            assertNull(safety.safeOutside(f.player, f.current), scenario);
        }
    }

    @Test
    void nativeModeNeverReadsOrLoadsUnloadedSourceOrExitChunks() {
        Fixture source = new Fixture();
        GatePromptSafety sourceSafety = source.nativeSafety(Axis.Z);
        when(source.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertFalse(sourceSafety.touchingGate(source.player, source.current));
        assertNull(sourceSafety.safeOutside(source.player, source.current));
        verify(source.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());

        Fixture exit = new Fixture();
        exit.min = new Vector(0, 56, 0);
        exit.max = new Vector(0, 58, 1);
        exit.current = new Location(exit.world, 0.5, 56, 0.5);
        GatePromptSafety exitSafety = exit.nativeSafety(Axis.Z);
        when(exit.world.isChunkLoaded(-1, 0)).thenReturn(false);
        exit.type(2, 56, 0, Material.STONE);
        assertTrue(exitSafety.touchingGate(exit.player, exit.current));
        assertNull(exitSafety.safeOutside(exit.player, exit.current));
        verify(exit.world, never()).getBlockAt(-2, 55, 0);
        verify(exit.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void productionNativeModeStillPrioritizesRegisteredGateGeometry() {
        Fixture f = new Fixture();
        f.current = new Location(f.world, 417.8, 57, 112.5);
        // No native Orientable data: success therefore requires the registered-gate path.
        GatePromptSafety safety = new GatePromptSafety(f.manager, true);
        assertTrue(safety.touchingGate(f.player, f.current));
        assertEquals(new Location(f.world, 419.5, 56, 112.5), safety.safeOutside(f.player, f.current));
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final PortalManager manager = mock(PortalManager.class);
        final GatePromptSafety safety = new GatePromptSafety(manager);
        final Map<Vector, Block> blocks = new HashMap<>();
        Vector min = new Vector(417, 56, 112), max = new Vector(417, 58, 113);
        Location current = new Location(world, 417.5, 56, 112.5);
        MVPortal portal;
        boolean purple = true;
        int floorY = 55;
        double height = 1.8;
        Axis nativeAxis;

        Fixture() {
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                    block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(player.getLocation()).thenAnswer(ignored -> current.clone());
            when(player.getBoundingBox()).thenAnswer(ignored -> new BoundingBox(current.getX() - 0.3, current.getY(),
                    current.getZ() - 0.3, current.getX() + 0.3, current.getY() + height, current.getZ() + 0.3));
            register();
        }

        void register() {
            PortalLocation selection = new PortalLocation();
            selection.setLocation(min, max, mock(MultiverseWorld.class));
            portal = mock(MVPortal.class);
            when(portal.getBukkitWorld()).thenReturn(Option.of(world));
            when(portal.getPortalLocation()).thenReturn(selection);
            when(manager.getAllPortals()).thenReturn(List.of(portal));
        }

        GatePromptSafety nativeSafety(Axis axis) {
            nativeAxis = axis;
            when(manager.getAllPortals()).thenReturn(List.of());
            return new GatePromptSafety(manager, true);
        }

        Block block(int x, int y, int z) {
            return blocks.computeIfAbsent(new Vector(x, y, z), ignored -> {
                Block block = mock(Block.class);
                boolean surface = purple && x >= min.getBlockX() && x <= max.getBlockX()
                        && y >= min.getBlockY() && y <= max.getBlockY() && z >= min.getBlockZ() && z <= max.getBlockZ();
                when(block.getType()).thenReturn(surface ? Material.NETHER_PORTAL : y == floorY ? Material.SANDSTONE : Material.AIR);
                when(block.isSolid()).thenReturn(y == floorY);
                when(block.getBoundingBox()).thenReturn(new BoundingBox(x, y, z, x + 1, y + 1, z + 1));
                if (surface && nativeAxis != null) {
                    Orientable data = mock(Orientable.class);
                    when(data.getAxis()).thenReturn(nativeAxis);
                    when(block.getBlockData()).thenReturn(data);
                }
                return block;
            });
        }

        void type(int x, int y, int z, Material material) {
            when(block(x, y, z).getType()).thenReturn(material);
        }
    }
}
