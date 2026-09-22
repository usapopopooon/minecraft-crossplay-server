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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
    void eachOverworldAndItsDedicatedDimensionsShareItemsButTheTwoSetsStaySeparate() {
        List<World> first = List.of(world("overworld"), world("the_nether"), world("the_end"));
        List<World> second = List.of(world("resource"), world("world_2_nether"), world("world_2_the_end"));
        for (List<World> sameGroup : List.of(first, second)) {
            for (World from : sameGroup) for (World to : sameGroup) {
                assertFalse(WorldTravelConfirmation.changesInventoryGroup(from, to));
            }
        }
        for (World from : first) for (World to : second) {
            assertTrue(WorldTravelConfirmation.changesInventoryGroup(from, to));
            assertTrue(WorldTravelConfirmation.changesInventoryGroup(to, from));
        }
        assertTrue(WorldTravelConfirmation.changesInventoryGroup(world("world_2_nether_unrelated"), second.getFirst()));
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
    void airFilledRegisteredGateConfirmsInPlaceAndProtectsTheApprovedArrival() {
        Fixture f = new Fixture();
        f.gate = "to_world_2";
        f.touchingGate = false; // Client-only surface: no real Nether portal touches the body.
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        f.drain();
        assertEquals(1, f.prompts.size());
        verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.prompts.getFirst().confirm.run();
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        verify(f.player).setPortalCooldown(20);
        verify(f.player).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
    }

    @Test
    void cancellingAirGateStaysQuietUntilPlayerExitsAndReenters() {
        Fixture f = new Fixture();
        f.gate = "to_world_2";
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        f.prompts.getFirst().cancel.run();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(1, f.prompts.size());
        f.gate = null;
        f.guard.onMove(new PlayerMoveEvent(f.player, f.location.clone(), f.location.clone().add(1, 0, 0)));
        f.gate = "to_world_2";
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(2, f.prompts.size());
        verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void cancelAndRepeatedGateAttemptsNeverTeleportOrSpamDialogs() {
        Fixture f = new Fixture();
        f.gate = "travel-gate";
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

    @Test
    void tracesRequestPromptCancelSuppressionAndMoveWithoutCoordinatesOrItems() {
        Fixture f = new Fixture();
        f.gate = "travel-gate";
        f.location = new Location(f.main, 12345.678, 65, 98765.432);
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        f.prompts.getFirst().cancel.run();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.gate = null;
        f.guard.onMove(new PlayerMoveEvent(f.player, f.location.clone(),
                f.location.clone().add(3, 0, 0)));
        assertTrue(f.traces.stream().anyMatch(line -> line.contains("request-new")));
        assertTrue(f.traces.stream().anyMatch(line -> line.contains("prompt opened=true")));
        assertTrue(f.traces.stream().anyMatch(line -> line.contains("cancel reason=callback")));
        assertTrue(f.traces.stream().anyMatch(line -> line.contains("request-suppressed")));
        assertTrue(f.traces.stream().anyMatch(line -> line.contains("clear reason=move")));
        String combined = String.join("\n", f.traces);
        assertFalse(combined.contains("12345.678"));
        assertFalse(combined.contains("98765.432"));
        assertFalse(combined.contains("Location{"));
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
    }

    @Test
    void nextTickInvalidTracesIdentifyEveryValidityPredicate() {
        for (String reason : List.of("offline", "dead", "expired", "world", "distance", "gate", "stale")) {
            Fixture f = new Fixture();
            if (reason.equals("gate")) f.gate = "first-gate";
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            String expected = switch (reason) {
                case "offline" -> { when(f.player.isOnline()).thenReturn(false); yield "online=false"; }
                case "dead" -> { when(f.player.isDead()).thenReturn(true); yield "dead=true"; }
                case "expired" -> { f.now = 31_000; yield "ageMs=31000"; }
                case "world" -> { f.location = new Location(f.second, 0, 65, 0); yield "sameWorld=false"; }
                case "distance" -> { f.location = new Location(f.main, 3, 65, 0); yield "distanceSquared=9.0"; }
                case "gate" -> { f.gate = "another-gate"; yield "sameGate=false"; }
                case "stale" -> {
                    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
                    when(event.getPlayer()).thenReturn(f.player);
                    f.guard.onQuit(event);
                    yield "current=false";
                }
                default -> throw new AssertionError(reason);
            };
            f.drain();
            assertTrue(f.traces.stream().anyMatch(line -> line.contains("next-tick-invalid")
                    && line.contains(expected)), reason + ": " + f.traces);
            assertTrue(f.prompts.isEmpty(), reason);
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        }
    }

    @Test
    void tracesSuccessfulReplayAndFailedPromptAndTeleport() {
        Fixture success = new Fixture();
        success.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        success.drain();
        success.prompts.getFirst().confirm.run();
        assertFalse(success.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        success.result.complete(true);
        success.drain();
        assertTrue(success.traces.stream().anyMatch(line -> line.contains("confirm valid=true")));
        assertTrue(success.traces.stream().anyMatch(line -> line.contains("replay-allowed")));
        assertTrue(success.traces.stream().anyMatch(line -> line.contains("replay-result success=true error=none")));

        Fixture unavailable = new Fixture();
        unavailable.show = false;
        unavailable.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        unavailable.drain();
        assertTrue(unavailable.traces.stream().anyMatch(line -> line.contains("prompt opened=false")));
        assertTrue(unavailable.traces.stream().anyMatch(line -> line.contains("cancel reason=prompt-unavailable")));

        Fixture failed = new Fixture();
        failed.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        failed.drain();
        failed.prompts.getFirst().confirm.run();
        failed.result.complete(false);
        failed.drain();
        assertTrue(failed.traces.stream().anyMatch(line -> line.contains("replay-result success=false error=none")));
    }

    @Test
    void quitDeathAndWorldChangeLogTheirDistinctClearReasons() {
        for (String reason : List.of("quit", "death", "world-change")) {
            Fixture f = new Fixture();
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (reason.equals("quit")) {
                PlayerQuitEvent event = mock(PlayerQuitEvent.class);
                when(event.getPlayer()).thenReturn(f.player);
                f.guard.onQuit(event);
            } else if (reason.equals("death")) {
                PlayerDeathEvent event = mock(PlayerDeathEvent.class);
                when(event.getPlayer()).thenReturn(f.player);
                f.guard.onDeath(event);
            } else {
                f.guard.onWorldChange(new PlayerChangedWorldEvent(f.player, f.main));
            }
            assertTrue(f.traces.stream().anyMatch(line -> line.contains("clear reason=" + reason)
                    && line.contains("current=false")));
        }
    }

    @Test
    void failingDiagnosticSinkCannotChangeConfirmationOrReplayBehavior() {
        Fixture f = new Fixture();
        f.failDiagnostics = true;
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        f.drain();
        assertEquals(1, f.prompts.size());
        f.prompts.getFirst().confirm.run();
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        f.result.complete(true);
        f.drain();
        verify(f.player, times(1)).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void outsideGateCommandsCanRetryAfterCancelOrExpiredConfirmation() {
        for (String reason : List.of("cancel", "expired-active", "expired-confirmed")) {
            Fixture f = new Fixture();
            f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND);
            f.drain();
            Prompt oldPrompt = f.prompts.getFirst();
            if (reason.equals("cancel")) {
                oldPrompt.cancel.run();
            } else {
                f.now = 31_000;
                if (reason.equals("expired-confirmed")) oldPrompt.confirm.run();
            }
            assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled(), reason);
            f.drain();
            assertEquals(2, f.prompts.size(), reason);
            oldPrompt.confirm.run();
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
            f.prompts.getLast().confirm.run();
            verify(f.player, times(1)).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.COMMAND));
        }
    }

    @Test
    void outsideGateCommandsCanRetryAfterPromptFailureOrNextTickInvalidity() {
        for (String reason : List.of("prompt-unavailable", "offline", "dead", "expired")) {
            Fixture f = new Fixture();
            f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND);
            switch (reason) {
                case "prompt-unavailable" -> f.show = false;
                case "offline" -> when(f.player.isOnline()).thenReturn(false);
                case "dead" -> when(f.player.isDead()).thenReturn(true);
                case "expired" -> f.now = 31_000;
                default -> throw new AssertionError(reason);
            }
            f.drain();
            int attempts = f.prompts.size();
            f.show = true;
            when(f.player.isOnline()).thenReturn(true);
            when(f.player.isDead()).thenReturn(false);
            assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled(), reason);
            f.drain();
            assertEquals(attempts + 1, f.prompts.size(), reason);
            f.prompts.getLast().confirm.run();
            verify(f.player, times(1)).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.COMMAND));
        }
    }

    @Test
    void outsideGateActivePromptAndApprovedReplayStillSuppressDuplicateRequests() {
        Fixture f = new Fixture();
        f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND);
        f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND);
        f.drain();
        f.now = 30_000; // Exactly the existing timeout boundary remains active.
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled());
        f.drain();
        assertEquals(1, f.prompts.size());
        f.prompts.getFirst().confirm.run();
        PlayerTeleportEvent different = new PlayerTeleportEvent(f.player, f.location,
                new Location(f.second, 99, 70, 99), PlayerTeleportEvent.TeleportCause.COMMAND);
        f.guard.onTeleport(different);
        f.drain();
        assertTrue(different.isCancelled());
        assertEquals(1, f.prompts.size());
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled());
    }

    @Test
    void expiredGateRequestsStaySuppressedUntilThePlayerExitsAndReenters() {
        Fixture f = new Fixture();
        f.gate = "travel-gate";
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        f.now = 31_000;
        f.prompts.getFirst().confirm.run();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(1, f.prompts.size());
        f.gate = null;
        f.guard.onMove(new PlayerMoveEvent(f.player, f.location.clone(), f.location.clone().add(1, 0, 0)));
        f.gate = "travel-gate";
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(2, f.prompts.size());
    }

    @Test
    void monitorTracesEarlierCancellationWithoutCreatingPendingRequestsOrChangingTheEvent() throws Exception {
        EventHandler annotation = WorldTravelConfirmation.class
                .getMethod("observeTeleport", PlayerTeleportEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, annotation.priority());
        assertFalse(annotation.ignoreCancelled());
        Fixture f = new Fixture();
        Player passenger = mock(Player.class);
        when(f.player.isSleeping()).thenReturn(true);
        when(f.player.isInsideVehicle()).thenReturn(true);
        when(f.player.getPassengers()).thenReturn(List.of(passenger));
        PlayerTeleportEvent event = new PlayerTeleportEvent(f.player, f.location, f.destination,
                PlayerTeleportEvent.TeleportCause.COMMAND);
        event.setCancelled(true);
        f.guard.onTeleport(event); // The HIGHEST listener skips an earlier cancellation.
        f.guard.observeTeleport(event);
        assertTrue(event.isCancelled());
        assertTrue(f.scheduled.isEmpty());
        assertTrue(f.prompts.isEmpty());
        assertEquals(1, f.traces.size());
        assertTrue(f.traces.getFirst().contains("teleport-monitor"));
        assertTrue(f.traces.getFirst().contains("cause=COMMAND cancelled=true pendingPresent=false"));
        assertTrue(f.traces.getFirst().contains("online=true dead=false sleeping=true passengersCount=1 insideVehicle=true"));
        event.setCancelled(false);
        f.guard.observeTeleport(event);
        assertFalse(event.isCancelled());
        assertTrue(f.traces.getLast().contains("cancelled=false pendingPresent=false"));
        assertTrue(f.scheduled.isEmpty());
    }

    @Test
    void monitorShowsOwnPendingCancellationButIgnoresSameGroupAndPreliminaryPortalEvents() {
        Fixture f = new Fixture();
        f.guard.observeTeleport(new PlayerPortalEvent(f.player, f.location, f.destination,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        f.guard.observeTeleport(new PlayerTeleportEvent(f.player, f.location,
                new Location(f.main, 99, 65, 99)));
        assertTrue(f.traces.isEmpty());
        PlayerTeleportEvent pending = f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.guard.observeTeleport(pending);
        assertTrue(f.traces.getLast().contains("cancelled=true pendingPresent=true"));
        assertEquals(1, f.scheduled.size());
        f.drain();
        assertEquals(1, f.prompts.size());
    }

    @Test
    void gateContactOutsideFootSelectionStepsBackInSameWorldBeforeOpeningAndReplaysOnce() {
        Fixture f = new Fixture();
        f.touchingGate = true; // Feet are outside MVP's selection, but the body touches purple blocks.
        f.outside = new Location(f.main, -2, 65, 0);
        when(f.player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                .thenAnswer(call -> {
                    Location target = call.getArgument(0);
                    PlayerTeleportEvent step = new PlayerTeleportEvent(f.player, f.location.clone(), target.clone(),
                            PlayerTeleportEvent.TeleportCause.PLUGIN);
                    f.guard.onTeleport(step);
                    assertFalse(step.isCancelled());
                    assertSame(f.main, target.getWorld());
                    f.location = target.clone();
                    f.touchingGate = false;
                    return true;
                });
        assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        f.scheduled.remove().run();
        assertEquals(f.outside, f.location);
        assertTrue(f.prompts.isEmpty());
        f.scheduled.remove().run();
        assertTrue(f.prompts.isEmpty());
        f.scheduled.remove().run();
        assertEquals(1, f.prompts.size());
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.prompts.getFirst().confirm.run();
        assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
        verify(f.player).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(f.player).setPortalCooldown(20);
        verify(f.player, never()).getInventory();
        verify(f.player, never()).getEnderChest();
    }

    @Test
    void gatePromptFailsClosedWithoutASafeNearbySameWorldExitOrWhenStepBackIsRejected() {
        for (String failure : List.of("missing", "different-world", "too-far", "rejected")) {
            Fixture f = new Fixture();
            f.touchingGate = true;
            f.outside = switch (failure) {
                case "missing" -> null;
                case "different-world" -> new Location(f.second, 0, 65, 0);
                case "too-far" -> new Location(f.main, 5, 65, 0);
                default -> new Location(f.main, -2, 65, 0);
            };
            assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
            f.drain();
            assertTrue(f.prompts.isEmpty(), failure);
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.drain();
            assertTrue(f.prompts.isEmpty(), failure);
            if (failure.equals("rejected")) {
                verify(f.player, times(1)).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
            }
            if (!failure.equals("rejected")) {
                verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
            }
        }
    }

    @Test
    void reenteringGateOrLeavingBeforeDelayedPromptCannotOpenOrApproveIt() {
        for (String change : List.of("gate", "move", "quit")) {
            Fixture f = new Fixture();
            f.touchingGate = true;
            f.outside = new Location(f.main, -2, 65, 0);
            when(f.player.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                    .thenAnswer(call -> { f.location = f.outside.clone(); f.touchingGate = false; return true; });
            f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.scheduled.remove().run();
            if (change.equals("gate")) f.touchingGate = true;
            else if (change.equals("move")) f.location.add(3, 0, 0);
            else {
                PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
                when(quit.getPlayer()).thenReturn(f.player);
                f.guard.onQuit(quit);
            }
            f.drain();
            assertTrue(f.prompts.isEmpty(), change);
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        }
    }

    @Test
    void cancelAfterStepBackAllowsExplicitCommandRetryWithoutSwitchingInventory() {
        Fixture f = new Fixture();
        f.touchingGate = true;
        f.outside = new Location(f.main, -2, 65, 0);
        when(f.player.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                .thenAnswer(call -> { f.location = f.outside.clone(); f.touchingGate = false; return true; });
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        Prompt old = f.prompts.getFirst();
        old.cancel.run();
        f.attempt(PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.drain();
        assertEquals(2, f.prompts.size());
        old.confirm.run();
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        f.prompts.getLast().confirm.run();
        verify(f.player).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void nativeMainGroupPortalsNeverConfirmStepBackOrChangeCooldown() {
        Fixture f = new Fixture();
        World nether = world("the_nether"), end = world("the_end");
        f.touchingGate = true;
        for (World other : List.of(nether, end)) {
            for (boolean returning : List.of(false, true)) {
                Location from = new Location(returning ? other : f.main, 0, 65, 0);
                Location to = new Location(returning ? f.main : other, 10, 65, 10);
                var cause = other == nether ? PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                        : PlayerTeleportEvent.TeleportCause.END_PORTAL;
                PlayerPortalEvent search = new PlayerPortalEvent(f.player, from, to, cause);
                f.guard.onTeleport(search);
                assertFalse(search.isCancelled());
                PlayerTeleportEvent finalExit = new PlayerTeleportEvent(f.player, from, to, cause);
                f.guard.onTeleport(finalExit);
                assertFalse(finalExit.isCancelled());
            }
        }
        assertTrue(f.scheduled.isEmpty());
        assertTrue(f.prompts.isEmpty());
        verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(f.player, never()).setPortalCooldown(anyInt());
    }

    @Test
    void nativeDedicatedWorld2PortalsNeedNoConfirmationInEitherDirection() {
        Fixture f = new Fixture();
        f.touchingGate = true;
        for (String key : List.of("world_2_nether", "world_2_the_end")) {
            World dimension = world(key);
            var cause = key.endsWith("nether") ? PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    : PlayerTeleportEvent.TeleportCause.END_PORTAL;
            for (boolean returning : List.of(false, true)) {
                Location from = new Location(returning ? dimension : f.second, 0, 65, 0);
                Location to = new Location(returning ? f.second : dimension, 10, 65, 10);
                PlayerPortalEvent search = new PlayerPortalEvent(f.player, from, to, cause);
                f.guard.onTeleport(search);
                assertFalse(search.isCancelled());
                PlayerTeleportEvent exit = new PlayerTeleportEvent(f.player, from, to, cause);
                f.guard.onTeleport(exit);
                assertFalse(exit.isCancelled());
            }
        }
        assertTrue(f.scheduled.isEmpty());
        assertTrue(f.prompts.isEmpty());
        verify(f.player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(f.player, never()).setPortalCooldown(anyInt());
    }

    @Test
    void nativeCrossGroupNetherPortalsStepBackAndConfirmBothDirectionsWithoutChangingItemsEarly() {
        for (boolean returning : List.of(false, true)) {
            Fixture f = new Fixture();
            World nether = world("the_nether");
            World origin = returning ? nether : f.second;
            World target = returning ? f.second : nether;
            f.location = new Location(origin, 0, 65, 0);
            f.destination.setWorld(target);
            f.touchingGate = true;
            f.outside = new Location(origin, -2, 65, 0);
            when(f.player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                    .thenAnswer(call -> {
                        Location proposed = call.getArgument(0);
                        assertSame(origin, proposed.getWorld());
                        PlayerTeleportEvent sameWorldStep = new PlayerTeleportEvent(f.player,
                                f.location.clone(), proposed.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                        f.guard.onTeleport(sameWorldStep);
                        assertFalse(sameWorldStep.isCancelled());
                        f.location = proposed.clone();
                        f.touchingGate = false;
                        return true;
                    });
            PlayerPortalEvent search = new PlayerPortalEvent(f.player, f.location, f.destination,
                    PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
            f.guard.onTeleport(search);
            assertFalse(search.isCancelled());
            assertTrue(f.scheduled.isEmpty());
            assertTrue(f.attempt(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL).isCancelled());
            f.drain();
            assertEquals(f.outside, f.location);
            assertEquals(1, f.prompts.size());
            verify(f.player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
            verify(f.player, never()).getInventory();
            verify(f.player, never()).getEnderChest();
            f.prompts.getFirst().confirm.run();
            assertFalse(f.attempt(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL).isCancelled());
            verify(f.player).teleportAsync(eq(f.destination), eq(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
            verify(f.player).setPortalCooldown(20);
        }
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
        final List<String> traces = new ArrayList<>();
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        long now;
        boolean show = true;
        boolean failDiagnostics;
        String gate;
        boolean touchingGate;
        Location outside;
        final WorldTravelConfirmation guard = new WorldTravelConfirmation(scheduled::add, (p, label, yes, no) -> {
            prompts.add(new Prompt(yes, no));
            return show;
        }, () -> now, l -> gate, line -> {
            if (failDiagnostics) throw new IllegalStateException("Diagnostic sink unavailable");
            traces.add(line);
        }, new WorldTravelConfirmation.GateSafety() {
            public boolean touchingGate(Player p, Location at) { return touchingGate; }
            public Location safeOutside(Player p, Location from) { return outside; }
        });

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
