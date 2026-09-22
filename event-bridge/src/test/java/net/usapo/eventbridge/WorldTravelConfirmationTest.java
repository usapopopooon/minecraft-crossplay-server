package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

final class WorldTravelConfirmationTest {
    @Test
    void mainNetherEndShareOneGroupButBothDirectionsToSecondNeedConfirmation() {
        World main = world("overworld"), nether = world("the_nether"), end = world("the_end");
        World second = world("resource");
        assertFalse(WorldTravelConfirmation.changesInventoryGroup(main, nether));
        assertFalse(WorldTravelConfirmation.changesInventoryGroup(end, main));
        assertTrue(WorldTravelConfirmation.changesInventoryGroup(main, second));
        assertTrue(WorldTravelConfirmation.changesInventoryGroup(second, nether));
        assertFalse(WorldTravelConfirmation.changesInventoryGroup(main, null));
    }

    @Test
    void confirmationCancelsOriginalAndReplaysTheExactResolvedLocationOnlyOnce() {
        Fixture f = new Fixture();
        PlayerTeleportEvent original = f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        assertTrue(original.isCancelled());
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.drain();
        assertEquals(1, f.prompts.size());
        original.getTo().setX(999); // The pending request must not alias mutable event state.
        f.prompts.getFirst().confirm.run();
        f.prompts.getFirst().confirm.run();
        verify(f.player, times(1)).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        PlayerTeleportEvent replay = f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        assertFalse(replay.isCancelled());
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
    }

    @Test
    void cancelAndRepeatedGateAttemptsNeverTeleportOrSpamDialogs() {
        Fixture f = new Fixture();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        f.prompts.getFirst().cancel.run();
        f.prompts.getFirst().confirm.run();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(1, f.prompts.size());
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void expiredOrMovedConfirmationIsNotAccepted() {
        for (boolean expired : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.drain();
            if (expired) f.now = 31_000;
            else f.location = new Location(f.main, 20, 65, 0);
            f.prompts.getFirst().confirm.run();
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        }
    }

    @Test
    void firstMoveOutsideInvalidatesRequestUsingEventDestinationBeforePlayerLocationChanges() {
        Fixture f = new Fixture();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        // Paper exposes the old player location while dispatching this event.
        f.guard.onMove(new PlayerMoveEvent(f.player, f.location.clone(),
                new Location(f.main, 20, 65, 0)));
        f.prompts.getFirst().confirm.run();
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(2, f.prompts.size());
    }

    @Test
    void quitDeathWorldChangeAndReconnectInvalidateStaleCallbacks() {
        for (String action : List.of("quit", "death", "world-change", "reconnect")) {
            Fixture f = new Fixture();
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.drain();
            switch (action) {
                case "quit" -> {
                    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
                    when(event.getPlayer()).thenReturn(f.player);
                    f.guard.onQuit(event);
                }
                case "death" -> {
                    PlayerDeathEvent event = mock(PlayerDeathEvent.class);
                    when(event.getPlayer()).thenReturn(f.player);
                    f.guard.onDeath(event);
                }
                case "world-change" -> f.guard.onWorldChange(new PlayerChangedWorldEvent(f.player, f.main));
                case "reconnect" -> {
                    Player replacement = mock(Player.class);
                    UUID playerId = f.player.getUniqueId();
                    when(replacement.getUniqueId()).thenReturn(playerId);
                    f.guard.onTeleport(new PlayerTeleportEvent(replacement, f.location, f.destination));
                }
            }
            f.prompts.getFirst().confirm.run();
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        }
    }

    @Test
    void preliminaryPortalEventIsIgnoredButFinalNativeExitRequiresConfirmationAndCooldown() {
        Fixture f = new Fixture();
        f.guard.onTeleport(new PlayerPortalEvent(f.player, f.location, f.destination,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        f.drain();
        assertTrue(f.prompts.isEmpty());
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL).isCancelled());
        f.drain();
        f.prompts.getFirst().confirm.run();
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL).isCancelled());
        verify(f.player).setPortalCooldown(10);
    }

    @Test
    void replayPermitIsBoundToTheActualDestinationAndDoesNotAuthorizeAnotherTeleport() {
        Fixture f = new Fixture();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        f.prompts.getFirst().confirm.run();
        PlayerTeleportEvent changed = new PlayerTeleportEvent(
                f.player, f.location, new Location(f.second, 99, 65, 99));
        f.guard.onTeleport(changed);
        assertTrue(changed.isCancelled());
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
    }

    @Test
    void failedPromptAndFailedTeleportStayCancelled() {
        Fixture noPrompt = new Fixture();
        noPrompt.show = false;
        assertTrue(noPrompt.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        noPrompt.drain();
        verify(noPrompt.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));

        Fixture failure = new Fixture();
        failure.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        failure.drain();
        failure.prompts.getFirst().confirm.run();
        failure.result.complete(false);
        failure.drain();
        assertTrue(failure.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        verify(failure.player, times(1)).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void alreadyCancelledOrSameGroupTeleportsAreNotPrompted() {
        Fixture f = new Fixture();
        PlayerTeleportEvent cancelled = new PlayerTeleportEvent(f.player, f.location, f.destination);
        cancelled.setCancelled(true);
        f.guard.onTeleport(cancelled);
        f.guard.onTeleport(new PlayerTeleportEvent(f.player, f.location, new Location(f.main, 99, 65, 99)));
        f.drain();
        assertTrue(f.prompts.isEmpty());
    }

    private static World world(String key) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return world;
    }

    private record Prompt(Runnable confirm, Runnable cancel) {}

    private static final class Fixture {
        final World main = world("overworld"), second = world("resource");
        final Player player = mock(Player.class);
        Location location = new Location(main, 0, 65, 0);
        final Location destination = new Location(second, 8, 70, 8);
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        final List<Prompt> prompts = new ArrayList<>();
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        long now;
        boolean show = true;
        final WorldTravelConfirmation guard = new WorldTravelConfirmation(scheduled::add, (p, label, yes, no) -> {
            prompts.add(new Prompt(yes, no));
            return show;
        }, () -> now, l -> null);

        Fixture() {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenAnswer(ignored -> location.clone());
            when(player.teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                    .thenReturn(result);
        }

        PlayerTeleportEvent attempt(PlayerTeleportEvent.TeleportCause cause) {
            PlayerTeleportEvent event = new PlayerTeleportEvent(player, location.clone(), destination.clone(), cause);
            guard.onTeleport(event);
            return event;
        }

        void drain() {
            while (!scheduled.isEmpty()) scheduled.remove().run();
        }
    }
}
