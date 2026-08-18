package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

final class MarketTransferCommandTest {
    private static final UUID SELLER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BUYER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TRANSFER_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void persistedPlayerHistoryMakesDeliveryRetryExactlyOnce() throws IOException {
        ItemStack item = item();
        MemoryRepository repository = new MemoryRepository(item);
        AtomicInteger additions = new AtomicInteger();
        AtomicReference<String> history = new AtomicReference<>();
        Player buyer = player(BUYER_ID, additions, history);
        List<String> responses = new ArrayList<>();
        MarketTransferCommand command = new MarketTransferCommand(
                playerId -> playerId.equals(BUYER_ID) ? buyer : null,
                repository,
                historyKey());
        CommandSender sender = sender(responses);
        String[] arguments = {
            "market-deliver", "17", BUYER_ID.toString(), TRANSFER_ID.toString()
        };

        assertTrue(command.onCommand(sender, null, "usapo-event-bridge", arguments));
        assertEquals(MarketListing.Status.DELIVERING, repository.listing.status());
        assertTrue(command.onCommand(sender, null, "usapo-event-bridge", arguments));

        assertEquals(1, additions.get());
        assertEquals(TRANSFER_ID.toString(), history.get());
        assertEquals(MarketListing.Status.SOLD, repository.listing.status());
        assertTrue(responses.getFirst().contains("|storage_error|delivering|duplicate"));
        assertTrue(responses.getLast().contains("|completed|sold|duplicate"));
    }

    @Test
    void failedPlayerSaveIsRetriedWithoutAddingTheItemAgain() throws IOException {
        ItemStack item = item();
        MemoryRepository repository = new MemoryRepository(item);
        repository.failFirstCompletion = false;
        AtomicInteger additions = new AtomicInteger();
        AtomicReference<String> history = new AtomicReference<>();
        AtomicInteger savesToFail = new AtomicInteger(1);
        Player buyer = player(BUYER_ID, additions, history, savesToFail);
        List<String> responses = new ArrayList<>();
        MarketTransferCommand command = new MarketTransferCommand(
                playerId -> playerId.equals(BUYER_ID) ? buyer : null,
                repository,
                historyKey());
        CommandSender sender = sender(responses);
        String[] arguments = {
            "market-deliver", "17", BUYER_ID.toString(), TRANSFER_ID.toString()
        };

        command.onCommand(sender, null, "usapo-event-bridge", arguments);
        command.onCommand(sender, null, "usapo-event-bridge", arguments);

        assertEquals(1, additions.get());
        assertEquals(0, savesToFail.get());
        assertEquals(MarketListing.Status.SOLD, repository.listing.status());
        assertTrue(responses.getFirst().contains("|storage_error|delivering|duplicate"));
        assertTrue(responses.getLast().contains("|completed|sold|duplicate"));
    }

    private static Player player(
            UUID playerId,
            AtomicInteger additions,
            AtomicReference<String> history) {
        return player(playerId, additions, history, new AtomicInteger());
    }

    private static Player player(
            UUID playerId,
            AtomicInteger additions,
            AtomicReference<String> history,
            AtomicInteger savesToFail) {
        ItemStack[] contents = new ItemStack[36];
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStorageContents" -> contents;
                    case "addItem" -> {
                        additions.incrementAndGet();
                        yield new HashMap<Integer, ItemStack>();
                    }
                    case "setStorageContents" -> null;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> history.get();
                    case "set" -> {
                        history.set((String) arguments[2]);
                        yield null;
                    }
                    case "remove" -> {
                        history.set(null);
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getInventory" -> inventory;
                    case "getPersistentDataContainer" -> data;
                    case "isOnline" -> true;
                    case "saveData" -> {
                        if (savesToFail.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                            throw new IllegalStateException("simulated player save failure");
                        }
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static CommandSender sender(List<String> responses) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof String message) {
                        responses.add(message);
                    }
                    return EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(2);
        when(item.getMaxStackSize()).thenReturn(64);
        return item;
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey historyKey() {
        return new NamespacedKey("usapo_event_bridge", "market_history");
    }

    private static final class MemoryRepository implements MarketRepository {
        private MarketListing listing;
        private boolean failFirstCompletion = true;

        private MemoryRepository(ItemStack item) {
            listing = new MarketListing(
                    17,
                    EVENT_ID,
                    SELLER_ID,
                    "Seller",
                    3_000,
                    item,
                    MarketListing.Status.ACTIVE,
                    null,
                    null);
        }

        @Override
        public MarketListing create(
                UUID eventId,
                UUID sellerId,
                String sellerName,
                int priceXp,
                ItemStack item) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MarketListing> find(long listingId) {
            return listing.id() == listingId ? Optional.of(listing) : Optional.empty();
        }

        @Override
        public Optional<MarketListing> findByEventId(UUID eventId) {
            return listing.eventId().equals(eventId) ? Optional.of(listing) : Optional.empty();
        }

        @Override
        public List<MarketListing> activeListings() {
            return listing.status() == MarketListing.Status.ACTIVE
                    ? List.of(listing)
                    : List.of();
        }

        @Override
        public MarketListing prepareTransfer(
                long listingId, UUID transferId, UUID recipientId) {
            listing = new MarketListing(
                    listing.id(),
                    listing.eventId(),
                    listing.sellerId(),
                    listing.sellerName(),
                    listing.priceXp(),
                    listing.item(),
                    MarketListing.Status.DELIVERING,
                    transferId,
                    recipientId);
            return listing;
        }

        @Override
        public MarketListing completeTransfer(
                long listingId,
                UUID transferId,
                MarketListing.Status completedStatus)
                throws IOException {
            if (failFirstCompletion) {
                failFirstCompletion = false;
                throw new IOException("simulated post-delivery storage failure");
            }
            listing = new MarketListing(
                    listing.id(),
                    listing.eventId(),
                    listing.sellerId(),
                    listing.sellerName(),
                    listing.priceXp(),
                    listing.item(),
                    completedStatus,
                    transferId,
                    listing.recipientId());
            return listing;
        }

        @Override
        public void abortTransfer(long listingId, UUID transferId) {
            listing = new MarketListing(
                    listing.id(),
                    listing.eventId(),
                    listing.sellerId(),
                    listing.sellerName(),
                    listing.priceXp(),
                    listing.item(),
                    MarketListing.Status.ACTIVE,
                    null,
                    null);
        }
    }
}
