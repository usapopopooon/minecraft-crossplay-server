package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class WorldModeMemoryTest {
    private static final NamespacedKey KEY = new NamespacedKey("usapoeventbridge", "world_2_game_mode");

    @Test
    void firstEntryDefaultsToSurvivalAndSuccessfulCommandWiresIntoPersistentMemory() {
        Fixture f = new Fixture(null, GameMode.CREATIVE);
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("overworld")));
        assertEquals(GameMode.SURVIVAL, f.mode.get());

        new WorldModeCommand(f.memory).onCommand(f.player, null, "mode", new String[0]);

        assertEquals(GameMode.CREATIVE, f.mode.get());
        assertEquals("CREATIVE", f.saved.get());
        f.world.set(world("overworld"));
        f.mode.set(GameMode.SURVIVAL); // Actual MVC destination enforcement.
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("resource")));
        assertEquals(GameMode.SURVIVAL, f.mode.get());
        assertEquals("CREATIVE", f.saved.get());
        f.world.set(world("resource"));
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("overworld")));
        assertEquals(GameMode.CREATIVE, f.mode.get());
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
        verify(f.player, never()).setTotalExperience(anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SURVIVAL"})
    void restoresBothSelectionsAfterNormalMultiverseJoinEnforcement(GameMode saved) {
        Fixture f = new Fixture(saved.name(), GameMode.CREATIVE);
        PlayerJoinEvent join = f.join();
        f.memory.beforeJoin(join);
        f.mode.set(GameMode.SURVIVAL);

        f.memory.afterJoin(join);

        assertEquals(saved, f.mode.get());
        assertEquals(saved.name(), f.saved.get());
        verify(f.player, never()).setOp(anyBoolean());
    }

    @Test
    void existingWorldTwoLogoutModeIsImportedBeforeFirstMultiverseJoin() {
        Fixture f = new Fixture(null, GameMode.CREATIVE);
        when(f.player.hasPlayedBefore()).thenReturn(true);
        when(f.player.isFlying()).thenReturn(true);
        when(f.player.getAllowFlight()).thenReturn(true);
        PlayerJoinEvent join = f.join();
        f.memory.beforeJoin(join);
        assertEquals("CREATIVE", f.saved.get());
        f.mode.set(GameMode.SURVIVAL);

        f.memory.afterJoin(join);

        assertEquals(GameMode.CREATIVE, f.mode.get());
        verify(f.player).setFlying(true);
    }

    @Test
    void newPlayerDoesNotImportAnUnrememberedCreativeMode() {
        Fixture f = new Fixture(null, GameMode.CREATIVE);
        PlayerJoinEvent join = f.join();
        f.memory.beforeJoin(join);
        f.memory.afterJoin(join);
        assertEquals(GameMode.SURVIVAL, f.mode.get());
        verify(f.player, never()).setFlying(anyBoolean());
    }

    @Test
    void currentWorldIsRecheckedIfAnotherJoinListenerMovesThePlayerAway() {
        Fixture f = new Fixture("CREATIVE", GameMode.CREATIVE);
        when(f.player.isFlying()).thenReturn(true);
        PlayerJoinEvent join = f.join();
        f.memory.beforeJoin(join);
        f.world.set(world("overworld"));
        f.mode.set(GameMode.SURVIVAL);
        f.memory.afterJoin(join);
        assertEquals(GameMode.SURVIVAL, f.mode.get());
        verify(f.player, never()).setFlying(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"overworld", "the_nether", "the_end", "world_2_nether", "world_2_the_end", "resource_backup"})
    void otherWorldsNeitherRestoreCreativeNorOverwriteTheWorldTwoSelection(String key) {
        Fixture f = new Fixture("CREATIVE", GameMode.SURVIVAL);
        f.world.set(world(key));
        f.memory.remember(f.player);
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("resource")));
        f.memory.beforeJoin(f.join());
        f.memory.afterJoin(f.join());
        f.memory.onQuit(f.quit());
        f.memory.beforeRespawn(f.respawn());
        assertEquals("CREATIVE", f.saved.get());
        verify(f.player, never()).setGameMode(any());
        verifyNoInteractions(f.data);
    }

    @Test
    void approvedDepartureCapturesActualModeBeforeDestinationEnforcement() {
        Fixture f = new Fixture("SURVIVAL", GameMode.CREATIVE);
        f.memory.beforeTeleport(f.teleport("overworld"));
        assertEquals("CREATIVE", f.saved.get());
        f.world.set(world("overworld"));
        f.mode.set(GameMode.SURVIVAL);
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("resource")));
        assertEquals("CREATIVE", f.saved.get());
    }

    @Test
    void cancelledAndSameWorldTeleportsDoNotReplaceSavedPreference() {
        Fixture f = new Fixture("SURVIVAL", GameMode.CREATIVE);
        PlayerTeleportEvent cancelled = f.teleport("overworld");
        cancelled.setCancelled(true);
        f.memory.beforeTeleport(cancelled);
        f.memory.beforeTeleport(f.teleport("resource"));
        assertEquals("SURVIVAL", f.saved.get());
        verifyNoInteractions(f.data);
    }

    @Test
    void quitAndRespawnCaptureTheCurrentSourceMode() {
        Fixture f = new Fixture("SURVIVAL", GameMode.CREATIVE);
        f.memory.beforeRespawn(f.respawn());
        assertEquals("CREATIVE", f.saved.get());
        f.mode.set(GameMode.SURVIVAL);
        f.memory.onQuit(f.quit());
        assertEquals("SURVIVAL", f.saved.get());
    }

    @Test
    void pluginShutdownCapturesOnlineWorldTwoModeBeforePaperSavesPlayers() throws Exception {
        Fixture f = new Fixture("SURVIVAL", GameMode.CREATIVE);
        Fixture elsewhere = new Fixture("CREATIVE", GameMode.SURVIVAL);
        elsewhere.world.set(world("overworld"));
        UsapoEventBridgePlugin plugin = mock(UsapoEventBridgePlugin.class, CALLS_REAL_METHODS);
        Server server = mock(Server.class);
        doReturn(server).when(plugin).getServer();
        doReturn(List.of(f.player, elsewhere.player)).when(server).getOnlinePlayers();
        var field = UsapoEventBridgePlugin.class.getDeclaredField("worldModeMemory");
        field.setAccessible(true);
        field.set(plugin, f.memory);

        plugin.onDisable();

        assertEquals("CREATIVE", f.saved.get());
        assertEquals("CREATIVE", elsewhere.saved.get());
        verifyNoInteractions(elsewhere.data);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPECTATOR", "ADVENTURE", "creative", "bogus"})
    void invalidSavedValuesNeverGrantAnotherMode(String value) {
        Fixture f = new Fixture(value, GameMode.CREATIVE);
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("overworld")));
        assertEquals(GameMode.SURVIVAL, f.mode.get());
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"ADVENTURE", "SPECTATOR"})
    void administratorOnlyModesDoNotReplaceTheLastValidPreference(GameMode mode) {
        Fixture f = new Fixture("SURVIVAL", mode);
        f.memory.remember(f.player);
        assertEquals("SURVIVAL", f.saved.get());
        verifyNoInteractions(f.data);
    }

    @Test
    void rejectedRestorationRetainsThePreferenceWithoutForcingOrRetrying() {
        Fixture f = new Fixture("CREATIVE", GameMode.SURVIVAL);
        doNothing().when(f.player).setGameMode(any());
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("overworld")));
        assertEquals(GameMode.SURVIVAL, f.mode.get());
        assertEquals("CREATIVE", f.saved.get());
        verify(f.player).setGameMode(GameMode.CREATIVE);
        verify(f.player).sendMessage("world_2の以前のゲームモードを復元できませんでした。管理者にご連絡ください。");
        f.memory.beforeRespawn(f.respawn());
        f.memory.beforeTeleport(f.teleport("overworld"));
        f.memory.remember(f.player); // Clean shutdown's capture.
        f.memory.onQuit(f.quit());
        assertEquals("CREATIVE", f.saved.get(), "Fallback is not the player's new choice");
    }

    @Test
    void successfulExplicitToggleCanReplaceAPreviouslyRejectedRestoration() {
        Fixture f = new Fixture("CREATIVE", GameMode.SURVIVAL);
        doNothing().when(f.player).setGameMode(any());
        f.memory.afterWorldChange(new PlayerChangedWorldEvent(f.player, world("overworld")));
        doAnswer(call -> { f.mode.set(call.getArgument(0)); return null; })
                .when(f.player).setGameMode(any());
        WorldModeCommand command = new WorldModeCommand(f.memory);
        command.onCommand(f.player, null, "mode", new String[0]);
        command.onCommand(f.player, null, "mode", new String[0]);
        assertEquals(GameMode.SURVIVAL, f.mode.get());
        assertEquals("SURVIVAL", f.saved.get());
    }

    @Test
    void listenersBracketVerifiedMultiversePrioritiesAndIgnoreCancelledTravel() throws Exception {
        assertPriority("beforeJoin", PlayerJoinEvent.class, EventPriority.LOWEST);
        assertPriority("afterJoin", PlayerJoinEvent.class, EventPriority.HIGHEST);
        assertPriority("afterWorldChange", PlayerChangedWorldEvent.class, EventPriority.HIGHEST);
        assertPriority("beforeRespawn", PlayerRespawnEvent.class, EventPriority.LOWEST);
        EventHandler handler = WorldModeMemory.class.getMethod("beforeTeleport", PlayerTeleportEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    private static void assertPriority(String method, Class<?> type, EventPriority priority) throws Exception {
        assertEquals(priority, WorldModeMemory.class.getMethod(method, type).getAnnotation(EventHandler.class).priority());
    }

    private static World world(String key) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return world;
    }

    private static final class Fixture {
        final WorldModeMemory memory = new WorldModeMemory(KEY);
        final Player player = mock(Player.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final AtomicReference<World> world = new AtomicReference<>(world("resource"));
        final AtomicReference<GameMode> mode;
        final AtomicReference<String> saved;

        Fixture(String stored, GameMode current) {
            saved = new AtomicReference<>(stored);
            mode = new AtomicReference<>(current);
            when(player.getWorld()).thenAnswer(ignored -> world.get());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getGameMode()).thenAnswer(ignored -> mode.get());
            when(player.getPersistentDataContainer()).thenReturn(data);
            when(player.hasPermission("usapo.mode.use")).thenReturn(true);
            when(data.get(KEY, PersistentDataType.STRING)).thenAnswer(ignored -> saved.get());
            doAnswer(call -> { saved.set(call.getArgument(2)); return null; })
                    .when(data).set(eq(KEY), eq(PersistentDataType.STRING), anyString());
            doAnswer(call -> { mode.set(call.getArgument(0)); return null; }).when(player).setGameMode(any());
        }

        PlayerJoinEvent join() {
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        PlayerQuitEvent quit() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        PlayerRespawnEvent respawn() {
            PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        PlayerTeleportEvent teleport(String destination) {
            World target = destination.equals("resource") ? world.get() : world(destination);
            return new PlayerTeleportEvent(player, new Location(world.get(), 1, 65, 1),
                    new Location(target, 1, 65, 1));
        }
    }
}
