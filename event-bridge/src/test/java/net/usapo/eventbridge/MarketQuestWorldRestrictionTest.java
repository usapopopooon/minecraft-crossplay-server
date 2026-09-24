package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

final class MarketQuestWorldRestrictionTest {
    private static final List<String> RESTRICTED =
            List.of("resource", "world_2_nether", "world_2_the_end");
    private static final UUID PLAYER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final NamespacedKey KEY = NamespacedKey.minecraft("restriction_test");

    @Test
    void everyMarketEntryPointIsDeniedBeforeReadingEscrowOrPublishingRequests() {
        for (String dimension : RESTRICTED) {
            Player player = player(dimension);
            MarketRepository repository = mock(MarketRepository.class);
            MarketRequestSink requests = mock(MarketRequestSink.class);
            BedrockMarketFormGateway forms = mock(BedrockMarketFormGateway.class);
            MarketCommand command = new MarketCommand(repository, requests, forms, KEY);
            for (String[] arguments : List.of(new String[0], new String[] {"sell", "100"},
                    new String[] {"buy", "1"}, new String[] {"cancel", "1"},
                    new String[] {"claim"}, new String[] {"balance"},
                    new String[] {"list"}, new String[] {"mine"})) {
                assertTrue(command.onCommand(player, null, "market", arguments));
            }
            verifyNoInteractions(repository, requests, forms);
            verify(player, never()).getInventory();
            verify(player, never()).getPersistentDataContainer();
        }
    }

    @Test
    void everyQuestEntryPointIsDeniedBeforeDraftRecoveryOrMutation() {
        for (String dimension : RESTRICTED) {
            Player player = player(dimension);
            QuestRepository repository = mock(QuestRepository.class);
            QuestActions actions = mock(QuestActions.class);
            BedrockQuestFormGateway forms = mock(BedrockQuestFormGateway.class);
            QuestCommand command = new QuestCommand(repository, actions, forms, KEY, KEY, KEY);
            for (String[] arguments : List.of(new String[0], new String[] {"create", "1", "24"},
                    new String[] {"confirm"}, new String[] {"discard"},
                    new String[] {"accept", "1"}, new String[] {"submit", "1"},
                    new String[] {"abandon", "1"}, new String[] {"cancel", "1"},
                    new String[] {"claim"}, new String[] {"list"}, new String[] {"mine"})) {
                assertTrue(command.onCommand(player, null, "quest", arguments));
            }
            verifyNoInteractions(repository, actions, forms);
            verify(player, never()).getInventory();
            verify(player, never()).getPersistentDataContainer();
        }
    }

    @Test
    void marketActionsRecheckWorldAfterEitherKindOfMenuWasOpened() {
        for (boolean bedrock : List.of(false, true)) {
            for (String dimension : RESTRICTED) {
                Player player = player("world");
                when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
                AtomicReference<Consumer<MarketFormAction>> callback = new AtomicReference<>();
                MarketRepository repository = mock(MarketRepository.class);
                MarketRequestSink requests = mock(MarketRequestSink.class);
                MarketCommand command = new MarketCommand(repository, requests,
                        (target, handler) -> {
                            if (bedrock) callback.set(handler);
                            return bedrock;
                        },
                        (target, handler) -> { callback.set(handler); return true; }, KEY);
                command.onCommand(player, null, "market", new String[0]);
                // The world_1 callback works, then the same stale form stops working.
                callback.get().accept(new MarketFormAction(MarketFormAction.Kind.BALANCE, 0, 0));
                verify(requests).publishRequest("balance", 0, 0, player);
                clearInvocations(requests, repository);
                doReturn(world(dimension)).when(player).getWorld();
                for (MarketFormAction.Kind kind : MarketFormAction.Kind.values()) {
                    callback.get().accept(new MarketFormAction(kind, 1, 100));
                }
                verifyNoInteractions(requests, repository);
                verify(player, never()).getInventory();
            }
        }
    }

    @Test
    void questActionsRecheckWorldAfterEitherKindOfMenuWasOpened() {
        for (boolean bedrock : List.of(false, true)) {
            for (String dimension : RESTRICTED) {
                Player player = player("world");
                when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
                AtomicReference<Consumer<QuestFormAction>> callback = new AtomicReference<>();
                QuestRepository repository = mock(QuestRepository.class);
                QuestActions actions = mock(QuestActions.class);
                QuestCommand command = new QuestCommand(repository, actions,
                        (target, handler) -> {
                            if (bedrock) callback.set(handler);
                            return bedrock;
                        },
                        (target, handler) -> { callback.set(handler); return true; }, KEY, KEY, KEY);
                command.onCommand(player, null, "quest", new String[0]);
                assertTrue(callback.get() != null);
                clearInvocations(repository, actions);
                doReturn(world(dimension)).when(player).getWorld();
                for (QuestFormAction.Kind kind : QuestFormAction.Kind.values()) {
                    callback.get().accept(new QuestFormAction(kind, 1, 1, 24));
                }
                verifyNoInteractions(repository, actions);
                verify(player, never()).getInventory();
            }
        }
    }

    @Test
    void marketRconDeliveryAndReturnRejectNewMutationInEveryRestrictedDimension() throws Exception {
        for (String dimension : RESTRICTED) {
            for (String operation : List.of("market-deliver", "market-return")) {
                Player player = player(dimension);
                when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
                MarketRepository repository = mock(MarketRepository.class);
                MarketListing listing = marketListing(MarketListing.Status.ACTIVE);
                when(repository.find(1)).thenReturn(Optional.of(listing));
                CommandSender sender = mock(CommandSender.class);
                MarketTransferCommand command = new MarketTransferCommand(ignored -> player, repository, KEY);
                command.onCommand(sender, null, "usapo-event-bridge",
                        new String[] {operation, "1", PLAYER.toString(), REQUEST.toString()});
                verify(sender).sendMessage(MarketTransferCommand.RESULT_PREFIX + REQUEST
                        + "|1|world_restricted|active|new");
                verify(repository, never()).prepareTransfer(anyLong(), any(), any());
                verify(player, never()).getInventory();
                verify(player, never()).saveData();
            }
        }
    }

    @Test
    void marketAlreadyDeliveredHistoryReconcilesWithoutAddingItemsInRestrictedWorld() throws Exception {
        Player player = player("resource");
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.get(KEY, PersistentDataType.STRING)).thenReturn(REQUEST.toString());
        MarketRepository repository = mock(MarketRepository.class);
        doReturn(Optional.of(marketListing(MarketListing.Status.DELIVERING))).when(repository).find(1);
        CommandSender sender = mock(CommandSender.class);
        new MarketTransferCommand(ignored -> player, repository, KEY).onCommand(sender, null,
                "usapo-event-bridge", new String[] {"market-deliver", "1", PLAYER.toString(), REQUEST.toString()});
        verify(repository).completeTransfer(1, REQUEST, MarketListing.Status.SOLD);
        verify(sender).sendMessage(MarketTransferCommand.RESULT_PREFIX + REQUEST + "|1|completed|sold|duplicate");
        verify(player, never()).getInventory();
    }

    @Test
    void marketFinalDuplicateAndRevocationMailboxReturnRemainAvailable() throws Exception {
        Player player = player("resource");
        MarketRepository repository = mock(MarketRepository.class);
        MarketListing sold = marketListing(MarketListing.Status.SOLD);
        when(repository.find(1)).thenReturn(Optional.of(sold));
        CommandSender sender = mock(CommandSender.class);
        MarketTransferCommand command = new MarketTransferCommand(ignored -> player, repository, KEY);
        command.onCommand(sender, null, "usapo-event-bridge",
                new String[] {"market-deliver", "1", PLAYER.toString(), REQUEST.toString()});
        verify(sender).sendMessage(MarketTransferCommand.RESULT_PREFIX + REQUEST + "|1|completed|sold|duplicate");
        MarketListing cancelled = marketListing(MarketListing.Status.CANCELLED);
        when(repository.returnToMailbox(1, REQUEST, PLAYER)).thenReturn(new MarketMailboxReturn(cancelled, false));
        command.onCommand(sender, null, "usapo-event-bridge",
                new String[] {"market-mailbox-return", "1", PLAYER.toString(), REQUEST.toString()});
        verify(repository).returnToMailbox(1, REQUEST, PLAYER);
        verify(sender).sendMessage(MarketTransferCommand.RESULT_PREFIX + REQUEST + "|1|completed|cancelled|new");
        verifyNoInteractions(player);
    }

    @Test
    void questSubmissionChecksTheWorldInsideSharedRconAndGameAction() throws Exception {
        for (String dimension : RESTRICTED) {
            Player player = player(dimension);
            when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            QuestRepository repository = mock(QuestRepository.class);
            QuestListing quest = mock(QuestListing.class);
            when(quest.status()).thenReturn(QuestListing.Status.ACCEPTED);
            when(repository.find(1)).thenReturn(Optional.of(quest));
            QuestActions actions = new QuestActions(repository, (changed, kind) -> {}, KEY, changed -> {});
            QuestActionException error = assertThrows(QuestActionException.class,
                    () -> actions.submit(1, REQUEST, player, 0));
            assertEquals(WorldEconomyPolicy.STATUS, error.code());
            CommandSender sender = mock(CommandSender.class);
            new QuestControlCommand(actions, repository, ignored -> player)
                    .onCommand(sender, null, "usapo-event-bridge", questArgs("quest-submit"));
            verify(sender).sendMessage(QuestControlCommand.RESULT_PREFIX + REQUEST
                    + "|1|world_restricted|accepted|new");
            verify(player, never()).getInventory();
            verify(repository, never()).complete(anyLong(), any(), any(), any(), anyLong());
        }
    }

    @Test
    void questCompletedSubmissionRetryReconcilesInRestrictedWorld() {
        Player player = player("world_2_the_end");
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        QuestRepository repository = mock(QuestRepository.class);
        QuestListing quest = mock(QuestListing.class);
        when(quest.id()).thenReturn(1L);
        when(quest.lastTransitionId()).thenReturn(REQUEST);
        when(repository.find(1)).thenReturn(Optional.of(quest));
        when(repository.isProcessed(1, REQUEST, "completed")).thenReturn(true);
        QuestActions actions = new QuestActions(repository, (changed, kind) -> {}, KEY, changed -> {});
        assertTrue(actions.submit(1, REQUEST, player, 0).duplicate());
        verify(player, never()).getInventory();
    }

    @Test
    void pendingQuestRefundWaitsForWorldOneWithoutDroppingItsRecoveryMarker() {
        for (String dimension : RESTRICTED) {
            Player player = player(dimension);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(player.getPersistentDataContainer()).thenReturn(data);
            when(data.get(KEY, PersistentDataType.STRING)).thenReturn("pending");
            PendingQuestSubmission pending = mock(PendingQuestSubmission.class);
            ItemStack item = mock(ItemStack.class);
            when(pending.questId()).thenReturn(1L);
            when(pending.item()).thenReturn(item);
            QuestRepository repository = mock(QuestRepository.class);
            QuestActions actions = new QuestActions(repository, (changed, kind) -> {}, KEY, changed -> {});
            try (var pendingType = mockStatic(PendingQuestSubmission.class);
                    var items = mockStatic(MarketItems.class)) {
                pendingType.when(() -> PendingQuestSubmission.decode("pending")).thenReturn(pending);
                assertTrue(actions.recoverPendingSubmission(player));
                verify(data, never()).remove(KEY);
                verify(player, never()).getInventory();
                verify(player, never()).saveData();

                doReturn(world("world")).when(player).getWorld();
                PlayerInventory inventory = mock(PlayerInventory.class);
                when(player.getInventory()).thenReturn(inventory);
                when(inventory.addItem(item)).thenReturn(new HashMap<>());
                items.when(() -> MarketItems.canFit(inventory, item)).thenReturn(true);
                items.when(() -> MarketItems.snapshot(inventory)).thenReturn(new ItemStack[36]);
                assertTrue(actions.recoverPendingSubmission(player));
                verify(inventory).addItem(item);
                verify(data).remove(KEY);
                verify(player).saveData();
            }
        }
    }

    @Test
    void questRconRejectsNewOnlineAcceptAndCancelButPreservesCleanupAndOfflineActions() {
        for (String dimension : RESTRICTED) {
            Player player = player(dimension);
            QuestRepository repository = mock(QuestRepository.class);
            QuestListing quest = mock(QuestListing.class);
            when(quest.status()).thenReturn(QuestListing.Status.OPEN);
            when(repository.find(1)).thenReturn(Optional.of(quest));
            QuestActions actions = mock(QuestActions.class);
            QuestTransition transition = new QuestTransition(quest, true);
            when(actions.accept(eq(1L), eq(REQUEST), eq(PLAYER), eq("Worker"), anyLong())).thenReturn(transition);
            when(actions.cancel(1, REQUEST, PLAYER)).thenReturn(transition);
            when(actions.invalidate(1, REQUEST, PLAYER)).thenReturn(transition);
            when(actions.releaseAssignment(eq(1L), eq(REQUEST), eq(PLAYER), anyLong())).thenReturn(transition);
            CommandSender sender = mock(CommandSender.class);
            QuestControlCommand command = new QuestControlCommand(actions, repository, ignored -> player);
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-accept"));
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-cancel"));
            verifyNoInteractions(actions);
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-invalidate"));
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-abandon"));
            verify(actions).invalidate(1, REQUEST, PLAYER);
            verify(actions).releaseAssignment(eq(1L), eq(REQUEST), eq(PLAYER), anyLong());
            when(player.isOnline()).thenReturn(false);
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-accept"));
            command.onCommand(sender, null, "usapo-event-bridge", questArgs("quest-cancel"));
            verify(actions).accept(eq(1L), eq(REQUEST), eq(PLAYER), eq("Worker"), anyLong());
            verify(actions).cancel(1, REQUEST, PLAYER);
        }
    }

    @Test
    void questRconCompletedAcceptAndCancelRetriesAreNotBlocked() {
        Player player = player("resource");
        QuestRepository repository = mock(QuestRepository.class);
        when(repository.isProcessed(1, REQUEST, "accepted")).thenReturn(true);
        when(repository.isProcessed(1, REQUEST, "cancelled")).thenReturn(true);
        QuestListing quest = mock(QuestListing.class);
        when(quest.status()).thenReturn(QuestListing.Status.CANCELLED);
        QuestActions actions = mock(QuestActions.class);
        when(actions.accept(eq(1L), eq(REQUEST), eq(PLAYER), eq("Worker"), anyLong()))
                .thenReturn(new QuestTransition(quest, true));
        when(actions.cancel(1, REQUEST, PLAYER)).thenReturn(new QuestTransition(quest, true));
        QuestControlCommand command = new QuestControlCommand(actions, repository, ignored -> player);
        command.onCommand(mock(CommandSender.class), null, "usapo-event-bridge", questArgs("quest-accept"));
        command.onCommand(mock(CommandSender.class), null, "usapo-event-bridge", questArgs("quest-cancel"));
        verify(actions).accept(eq(1L), eq(REQUEST), eq(PLAYER), eq("Worker"), anyLong());
        verify(actions).cancel(1, REQUEST, PLAYER);
        verifyNoInteractions(player);
    }

    private static String[] questArgs(String operation) {
        if (operation.equals("quest-accept")) {
            return new String[] {operation, "1", PLAYER.toString(),
                    Base64.getEncoder().encodeToString("Worker".getBytes(StandardCharsets.UTF_8)), REQUEST.toString()};
        }
        return new String[] {operation, "1", PLAYER.toString(), REQUEST.toString()};
    }

    private static MarketListing marketListing(MarketListing.Status status) {
        MarketListing listing = mock(MarketListing.class);
        when(listing.status()).thenReturn(status);
        when(listing.sellerId()).thenReturn(PLAYER);
        when(listing.recipientId()).thenReturn(PLAYER);
        when(listing.transferId()).thenReturn(REQUEST);
        return listing;
    }

    private static Player player(String dimension) {
        Player player = mock(Player.class);
        doReturn(world(dimension)).when(player).getWorld();
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static World world(String dimension) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(dimension));
        return world;
    }
}
