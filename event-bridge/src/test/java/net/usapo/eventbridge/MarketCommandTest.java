package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class MarketCommandTest {
    private static final UUID SELLER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BUYER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void fullHeldStackIsEscrowedAndExactListingPriceIsUsed() {
        MemoryRepository repository = new MemoryRepository();
        List<String> events = new ArrayList<>();
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing listing) {
                        events.add("listing|" + listing.id() + "|" + listing.item().getAmount()
                                + "|" + listing.priceXp());
                    }

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {
                        events.add(kind + "|" + listingId + "|" + priceXp + "|"
                                + player.getUniqueId());
                    }
                },
                (player, handler) -> false,
                pendingKey());
        Material ancientDebris = material("ancient_debris");
        AtomicReference<ItemStack> sellerHand = new AtomicReference<>(item(ancientDebris, 2));
        Player seller = player("Seller", SELLER_ID, sellerHand, new ArrayList<>());

        assertTrue(command.onCommand(seller, null, "market", new String[] {"sell", "3000"}));
        MarketListing listing = repository.find(1).orElseThrow();
        assertEquals(ancientDebris, listing.item().getType());
        assertEquals(2, listing.item().getAmount());
        assertEquals(3_000, listing.priceXp());
        assertEquals(null, sellerHand.get());

        Player buyer = player(
                "Buyer",
                BUYER_ID,
                new AtomicReference<>(),
                new ArrayList<>());
        assertTrue(command.onCommand(buyer, null, "market", new String[] {"buy", "1"}));
        assertTrue(command.onCommand(buyer, null, "market", new String[] {"balance"}));

        assertEquals(
                List.of(
                        "listing|1|2|3000",
                        "buy|1|3000|" + BUYER_ID,
                        "balance|0|0|" + BUYER_ID),
                events);
    }

    @Test
    void onlySellerCanRequestCancellation() {
        MemoryRepository repository = new MemoryRepository();
        List<String> events = new ArrayList<>();
        MarketRequestSink sink = new MarketRequestSink() {
            @Override
            public void publishListing(MarketListing listing) {}

            @Override
            public void publishRequest(String kind, long listingId, int priceXp, Player player) {
                events.add(kind + "|" + player.getUniqueId());
            }
        };
        MarketCommand command =
                new MarketCommand(repository, sink, (player, handler) -> false, pendingKey());
        repository.create(
                EVENT_ID, SELLER_ID, "Seller", 50, item(material("diamond"), 1));
        List<String> buyerMessages = new ArrayList<>();
        Player buyer = player(
                "Buyer",
                BUYER_ID,
                new AtomicReference<>(),
                buyerMessages);

        command.onCommand(buyer, null, "market", new String[] {"cancel", "1"});

        assertTrue(events.isEmpty());
        assertTrue(buyerMessages.stream().anyMatch(message -> message.contains("自分の出品")));
    }

    @Test
    void commandHelpAndDisplayedPricesIdentifyServerXp() {
        MemoryRepository repository = new MemoryRepository();
        repository.create(
                EVENT_ID, SELLER_ID, "Seller", 3_000, item(material("ancient_debris"), 2));
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing listing) {}

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {}
                },
                (player, handler) -> false,
                pendingKey());
        List<String> messages = new ArrayList<>();
        Player buyer = player("Buyer", BUYER_ID, new AtomicReference<>(), messages);

        command.onCommand(buyer, null, "market", new String[] {});
        command.onCommand(buyer, null, "market", new String[] {"list"});
        command.onCommand(buyer, null, "market", new String[] {"buy", "1"});

        assertTrue(messages.contains(
                "出品: /market sell <合計価格>（価格はサーバーXP・手に持ったスタック全部）"));
        assertTrue(messages.contains("サーバーXP残高: /market balance"));
        assertTrue(messages.stream().anyMatch(message ->
                message.contains("#1 ancient debris x2 / 3000 サーバーXP / Seller")));
        assertTrue(messages.stream().anyMatch(message ->
                message.contains("購入を確認しています: #1 ancient debris x2 / 3000 サーバーXP")));
    }

    @Test
    void failedListingPublicationKeepsEscrowAndRecoversWithoutDuplicatingItem() {
        MemoryRepository repository = new MemoryRepository();
        AtomicBoolean failPublication = new AtomicBoolean(true);
        List<UUID> publishedEvents = new ArrayList<>();
        MarketRequestSink sink = new MarketRequestSink() {
            @Override
            public void publishListing(MarketListing listing) {
                publishedEvents.add(listing.eventId());
                if (failPublication.getAndSet(false)) {
                    throw new IllegalStateException("temporary publication failure");
                }
            }

            @Override
            public void publishRequest(
                    String kind, long listingId, int priceXp, Player player) {}
        };
        MarketCommand command =
                new MarketCommand(repository, sink, (player, handler) -> false, pendingKey());
        ItemStack escrowItem = item(material("ancient_debris"), 2);
        AtomicReference<ItemStack> sellerHand = new AtomicReference<>(escrowItem);
        List<String> messages = new ArrayList<>();
        Player seller = player("Seller", SELLER_ID, sellerHand, messages);

        command.onCommand(seller, null, "market", new String[] {"sell", "3000"});

        assertEquals(null, sellerHand.get());
        assertEquals(1, repository.activeListings().size());
        assertTrue(messages.stream().anyMatch(message -> message.contains("一覧への反映待ち")));

        PendingMarketEscrow recovered =
                new PendingMarketEscrow(publishedEvents.getFirst(), 3_000, escrowItem);
        try (MockedStatic<PendingMarketEscrow> decoder = mockStatic(PendingMarketEscrow.class)) {
            decoder.when(() -> PendingMarketEscrow.decode(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(recovered);
            command.onCommand(seller, null, "market", new String[] {"list"});
        }

        assertEquals(null, sellerHand.get());
        assertEquals(1, repository.activeListings().size());
        assertEquals(2, publishedEvents.size());
        assertEquals(publishedEvents.get(0), publishedEvents.get(1));
        assertTrue(messages.stream().anyMatch(message -> message.contains("出品を復旧")));
    }

    private static Player player(
            String name,
            UUID uniqueId,
            AtomicReference<ItemStack> mainHand,
            List<String> messages) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getItemInMainHand" -> mainHand.get();
                    case "setItemInMainHand" -> {
                        mainHand.set(arguments[0] == null ? null : ((ItemStack) arguments[0]).clone());
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        AtomicReference<String> pendingEscrow = new AtomicReference<>();
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> pendingEscrow.get();
                    case "set" -> {
                        pendingEscrow.set((String) arguments[2]);
                        yield null;
                    }
                    case "remove" -> {
                        pendingEscrow.set(null);
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uniqueId;
                    case "getInventory" -> inventory;
                    case "getPersistentDataContainer" -> data;
                    case "isOnline" -> true;
                    case "saveData" -> null;
                    case "sendMessage" -> {
                        if (arguments != null
                                && arguments.length == 1
                                && arguments[0] instanceof String message) {
                            messages.add(message);
                        }
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        String materialKey = material.getKey().getKey();
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.serialize()).thenReturn(Map.of("type", materialKey, "amount", amount));
        return item;
    }

    @SuppressWarnings("deprecation")
    private static Material material(String key) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return material;
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey pendingKey() {
        return new NamespacedKey("usapo_event_bridge", "pending_market_escrow");
    }

    private static final class MemoryRepository implements MarketRepository {
        private final Map<Long, MarketListing> listings = new LinkedHashMap<>();

        @Override
        public MarketListing create(
                UUID eventId,
                UUID sellerId,
                String sellerName,
                int priceXp,
                ItemStack item) {
            long id = listings.size() + 1L;
            MarketListing listing = new MarketListing(
                    id,
                    eventId,
                    sellerId,
                    sellerName,
                    priceXp,
                    item,
                    MarketListing.Status.ACTIVE,
                    null,
                    null);
            listings.put(id, listing);
            return listing;
        }

        @Override
        public Optional<MarketListing> find(long listingId) {
            return Optional.ofNullable(listings.get(listingId));
        }

        @Override
        public Optional<MarketListing> findByEventId(UUID eventId) {
            return listings.values().stream()
                    .filter(listing -> listing.eventId().equals(eventId))
                    .findFirst();
        }

        @Override
        public List<MarketListing> activeListings() {
            return listings.values().stream()
                    .filter(listing -> listing.status() == MarketListing.Status.ACTIVE)
                    .toList();
        }

        @Override
        public MarketListing prepareTransfer(long listingId, UUID transferId, UUID recipientId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MarketListing completeTransfer(
                long listingId,
                UUID transferId,
                MarketListing.Status completedStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortTransfer(long listingId, UUID transferId) throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
