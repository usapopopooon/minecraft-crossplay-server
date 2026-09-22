package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.external.vavr.control.Option;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.PortalLocation;
import org.mvplugins.multiverse.portals.utils.PortalManager;

final class CyanGateOverlayTest {
    @Test
    void realSafetyAndConfirmationIgnoreClientOnlyMembraneAndOpenPromptWithoutMovingPlayer() {
        Fixture f = new Fixture();
        when(f.world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(f.player.getBoundingBox()).thenReturn(new BoundingBox(0.7, 65, 0.7, 1.3, 66.8, 1.3));
        f.overlay.run();
        GatePromptSafety safety = new GatePromptSafety(f.manager, true);
        assertFalse(safety.touchingGate(f.player, f.at));
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        List<Player> prompted = new ArrayList<>();
        WorldTravelConfirmation travel = new WorldTravelConfirmation(scheduled::add,
                (player, label, confirm, cancel) -> prompted.add(player), () -> 0,
                location -> "to_world_2", ignored -> {}, safety);
        World second = mock(World.class);
        when(second.getKey()).thenReturn(NamespacedKey.minecraft("resource"));
        PlayerTeleportEvent attempt = new PlayerTeleportEvent(f.player, f.at,
                new Location(second, 0, 70, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        travel.onTeleport(attempt);
        while (!scheduled.isEmpty()) scheduled.remove().run();
        assertTrue(attempt.isCancelled());
        assertEquals(List.of(f.player), prompted);
        verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.assertNoWorldMutation();
    }

    @Test
    void acceptedPackShowsBothOrientationsOnlyToNearbyViewersWithoutChangingRealBlocks() {
        Fixture f = new Fixture();
        f.gates.add(portal("to_world_1", f.world, new Vector(8, 64, 0), new Vector(8, 66, 2)));
        f.overlay.run();
        verify(f.player, times(12)).sendBlockChange(any(Location.class), same(f.xPlane));
        verify(f.player, times(9)).sendBlockChange(any(Location.class), same(f.zPlane));
        f.assertNoWorldMutation();
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
        assertTrue(CyanGateOverlay.X_PLANE_STATE.contains("powered=false"));
        assertTrue(CyanGateOverlay.Z_PLANE_STATE.contains("powered=true"));
    }

    @Test
    void framesLiquidsDecorationsRealTripwiresAndPurplePortalsAreNeverHidden() {
        Fixture f = new Fixture();
        List<Material> materials = List.of(Material.GLOWSTONE, Material.WATER, Material.LAVA,
                Material.VINE, Material.TRIPWIRE, Material.NETHER_PORTAL, Material.CAVE_AIR,
                Material.VOID_AIR, Material.END_PORTAL);
        for (int i = 0; i < materials.size(); i++) {
            when(f.block(i / 3, 64 + i % 3, 0).getType()).thenReturn(materials.get(i));
        }
        f.overlay.run();
        verify(f.player, times(3)).sendBlockChange(any(Location.class), same(f.xPlane));
        f.assertNoWorldMutation();
    }

    @Test
    void missingOrDeclinedPackUsesOnlyParticlesThenAcceptanceShowsSurfaceAndRevocationRestoresIt() {
        Fixture f = new Fixture();
        f.ready = false;
        f.overlay.run();
        verify(f.player, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        assertEquals(1, calls(f.player, "spawnParticle"));
        f.ready = true;
        f.overlay.run();
        verify(f.player, times(12)).sendBlockChange(any(Location.class), same(f.xPlane));
        f.ready = false;
        f.overlay.run();
        verify(f.player, times(12)).sendBlockChange(any(Location.class), same(f.real));
        assertEquals(2, calls(f.player, "spawnParticle"));
        f.assertNoWorldMutation();
    }

    @Test
    void unrelatedRegisteredGateAndUnregisteredNativePortalsAreNeverInspected() {
        Fixture f = new Fixture();
        MVPortal other = portal("not_approved", f.world, new Vector(10, 64, 0), new Vector(13, 66, 0));
        f.gates.clear();
        f.gates.add(other);
        f.overlay.run();
        verify(other, never()).getPortalLocation();
        verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(f.player, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void netherEndInvalidSelectionsAndMissingWorldAreSkipped() {
        for (World.Environment environment : List.of(World.Environment.NETHER, World.Environment.THE_END)) {
            Fixture f = new Fixture();
            when(f.world.getEnvironment()).thenReturn(environment);
            f.overlay.run();
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
        Fixture invalid = new Fixture();
        when(invalid.gates.getFirst().getPortalLocation()).thenReturn(new PortalLocation());
        invalid.overlay.run();
        verify(invalid.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        Fixture missing = new Fixture();
        when(missing.gates.getFirst().getBukkitWorld()).thenReturn(Option.none());
        missing.overlay.run();
        verify(missing.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void unloadedChunksUnsentChunksAndDistantPlayersReceiveNoFakeBlocks() {
        for (String state : List.of("unloaded", "unsent", "far", "other-world", "nan")) {
            Fixture f = new Fixture();
            switch (state) {
                case "unloaded" -> when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
                case "unsent" -> when(f.player.isChunkSent(anyLong())).thenReturn(false);
                case "far" -> f.at = new Location(f.world, 100, 65, 0);
                case "other-world" -> f.at = new Location(mock(World.class), 1, 65, 0);
                case "nan" -> f.at = new Location(f.world, Double.NaN, 65, 0);
                default -> fail(state);
            }
            f.overlay.run();
            verify(f.player, never()).sendBlockChange(any(Location.class), any(BlockData.class));
            verify(f.world, never()).getChunkAt(anyInt(), anyInt());
            if (!state.equals("unsent")) verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void leavingRangeRemovingGateOrPlacingSolidBlockRestoresCurrentRealState() {
        for (String action : List.of("leave", "remove", "build")) {
            Fixture f = new Fixture();
            f.overlay.run();
            BlockData newReal = mock(BlockData.class);
            Block changed = f.block(0, 64, 0);
            when(changed.getBlockData()).thenReturn(newReal);
            if (action.equals("leave")) f.at = new Location(f.world, 100, 65, 0);
            else if (action.equals("remove")) f.gates.clear();
            else when(changed.getType()).thenReturn(Material.STONE);
            f.overlay.run();
            verify(f.player).sendBlockChange(eq(new Location(f.world, 0, 64, 0)), same(newReal));
            f.assertNoWorldMutation();
        }
    }

    @Test
    void clientChunkUnloadAndReloadForgetOldIllusionsAndResendAfterChunkIsSent() {
        Fixture f = new Fixture();
        f.overlay.run();
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(f.world);
        f.overlay.onChunkUnload(new PlayerChunkUnloadEvent(chunk, f.player));
        when(f.player.isChunkSent(anyLong())).thenReturn(false);
        f.overlay.run();
        verify(f.player, never()).sendBlockChange(any(Location.class), same(f.real));
        when(f.player.isChunkSent(anyLong())).thenReturn(true);
        f.overlay.run();
        verify(f.player, times(24)).sendBlockChange(any(Location.class), same(f.xPlane));
    }

    @Test
    void disconnectWorldChangeAndWorldUnloadDiscardOldWorldPackets() {
        for (String action : List.of("quit", "world-change", "world-unload")) {
            Fixture f = new Fixture();
            f.overlay.run();
            if (action.equals("quit")) {
                PlayerQuitEvent event = mock(PlayerQuitEvent.class);
                when(event.getPlayer()).thenReturn(f.player);
                f.overlay.onQuit(event);
            } else if (action.equals("world-change")) {
                f.overlay.onWorldChange(new PlayerChangedWorldEvent(f.player, f.world));
            } else {
                f.overlay.onWorldUnload(new WorldUnloadEvent(f.world));
            }
            f.overlay.close();
            verify(f.player, never()).sendMultiBlockChange(anyMap());
        }
    }

    @Test
    void cancelledWorldUnloadKeepsStateSoDisableRestoresRealBlocks() {
        Fixture f = new Fixture();
        f.overlay.run();
        WorldUnloadEvent event = new WorldUnloadEvent(f.world);
        event.setCancelled(true);
        f.overlay.onWorldUnload(event);
        f.overlay.close();
        verify(f.player).sendMultiBlockChange(anyMap());
    }

    @Test
    void disableBatchesCurrentRealBlocksAndPreventsFurtherRendering() {
        Fixture f = new Fixture();
        f.overlay.run();
        BlockData changed = mock(BlockData.class);
        when(f.block(0, 64, 0).getBlockData()).thenReturn(changed);
        f.overlay.close();
        f.overlay.close();
        f.overlay.run();
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> real = ArgumentCaptor.forClass(Map.class);
        verify(f.player).sendMultiBlockChange(real.capture());
        assertEquals(12, real.getValue().size());
        assertTrue(real.getValue().containsValue(changed));
        assertTrue(real.getValue().containsValue(f.real));
        assertFalse(real.getValue().containsValue(f.xPlane));
        verify(f.player, times(12)).sendBlockChange(any(Location.class), same(f.xPlane));
    }

    @Test
    void perPlayerAndSharedPacketBudgetsAreBoundedAndLaterPlayersEventuallyRender() {
        Fixture f = new Fixture();
        f.gates.clear();
        f.gates.add(portal("to_world_2", f.world, new Vector(0, 64, 0), new Vector(31, 95, 0)));
        for (int i = 1; i < 20; i++) f.players.add(f.player());
        f.overlay.run();
        assertEquals(CyanGateOverlay.MAX_UPDATES_PER_RUN,
                f.players.stream().mapToLong(player -> calls(player, "sendBlockChange")).sum());
        for (Player player : f.players) assertTrue(calls(player, "sendBlockChange") <= CyanGateOverlay.MAX_UPDATES_PER_PLAYER);
        for (int i = 0; i < 5; i++) f.overlay.run();
        for (Player player : f.players) assertTrue(calls(player, "sendBlockChange") > 0);
        f.assertNoWorldMutation();
    }

    @Test
    void registryScanIsBoundedAndNegativeChunkCoordinatesRetainBothAxes() {
        Fixture f = new Fixture();
        f.gates.clear();
        for (int i = 0; i < 100; i++) {
            MVPortal portal = mock(MVPortal.class);
            when(portal.getName()).thenReturn("other_" + i);
            f.gates.add(portal);
        }
        f.overlay.run();
        assertEquals(CyanGateOverlay.MAX_PORTALS_PER_RUN,
                f.gates.stream().mapToLong(portal -> calls(portal, "getName")).sum());
        assertEquals(-1L, CyanGateOverlay.chunkKey(-1, -1));
        assertEquals(0xffffffffL, CyanGateOverlay.chunkKey(-1, 0));
        assertEquals(0xffffffff00000000L, CyanGateOverlay.chunkKey(0, -1));
    }

    private static long calls(Object mock, String method) {
        return mockingDetails(mock).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).count();
    }

    private static MVPortal portal(String name, World world, Vector min, Vector max) {
        MVPortal portal = mock(MVPortal.class);
        PortalLocation location = new PortalLocation();
        location.setLocation(min, max, mock(MultiverseWorld.class));
        when(portal.getName()).thenReturn(name);
        when(portal.getPortalLocation()).thenReturn(location);
        when(portal.getBukkitWorld()).thenReturn(Option.of(world));
        return portal;
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final PortalManager manager = mock(PortalManager.class);
        final BlockData xPlane = mock(BlockData.class), zPlane = mock(BlockData.class), real = mock(BlockData.class);
        final Map<Vector, Block> blocks = new HashMap<>();
        final List<MVPortal> gates = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        final Player player = player();
        Location at = new Location(world, 1, 65, 1);
        boolean ready = true;
        final CyanGateOverlay overlay = new CyanGateOverlay(manager, () -> players, ignored -> ready, xPlane, zPlane);

        Fixture() {
            players.add(player);
            gates.add(portal("to_world_2", world, new Vector(0, 64, 0), new Vector(3, 66, 0)));
            when(manager.getAllPortals()).thenReturn(gates);
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                    block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        }

        Player player() {
            Player result = mock(Player.class);
            when(result.getUniqueId()).thenReturn(UUID.randomUUID());
            when(result.isOnline()).thenReturn(true);
            when(result.getLocation()).thenAnswer(ignored -> at);
            when(result.getWorld()).thenAnswer(ignored -> at.getWorld());
            when(result.isChunkSent(anyLong())).thenReturn(true);
            return result;
        }

        Block block(int x, int y, int z) {
            return blocks.computeIfAbsent(new Vector(x, y, z), ignored -> {
                Block result = mock(Block.class);
                when(result.getType()).thenReturn(Material.AIR);
                when(result.getBlockData()).thenReturn(real);
                return result;
            });
        }

        void assertNoWorldMutation() {
            for (Block block : blocks.values()) {
                assertTrue(mockingDetails(block).getInvocations().stream()
                        .noneMatch(call -> call.getMethod().getName().startsWith("set")));
            }
        }
    }
}
