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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
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
                message.contains("#1 古代の残骸 x2 / 3000 サーバーXP / Seller")));
        assertTrue(messages.stream().anyMatch(message ->
                message.contains("購入を確認しています: #1 古代の残骸 x2 / 3000 サーバーXP")));
    }

    @Test
    void javaMenuIsUsedWhenBedrockFormIsUnavailableAndSharesTheActionPath() {
        MemoryRepository repository = new MemoryRepository();
        List<String> events = new ArrayList<>();
        AtomicBoolean javaMenuOpened = new AtomicBoolean();
        AtomicReference<java.util.function.Consumer<MarketFormAction>> selection =
                new AtomicReference<>();
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing listing) {}

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {
                        events.add(kind + "|" + listingId + "|" + priceXp);
                    }
                },
                (player, handler) -> false,
                (player, handler) -> {
                    javaMenuOpened.set(true);
                    selection.set(handler);
                    return true;
                },
                pendingKey());
        List<String> messages = new ArrayList<>();
        Player buyer = player("Buyer", BUYER_ID, new AtomicReference<>(), messages);

        command.onCommand(buyer, null, "market", new String[0]);
        selection.get().accept(new MarketFormAction(MarketFormAction.Kind.BALANCE, 0, 0));

        assertTrue(javaMenuOpened.get());
        assertEquals(List.of("balance|0|0"), events);
        assertTrue(messages.stream().noneMatch(message -> message.startsWith("商品一覧:")));
    }

    @Test
    void bedrockFormRemainsPreferredOverTheJavaMenu() {
        MemoryRepository repository = new MemoryRepository();
        AtomicBoolean javaMenuOpened = new AtomicBoolean();
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing listing) {}

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {}
                },
                (player, handler) -> true,
                (player, handler) -> {
                    javaMenuOpened.set(true);
                    return true;
                },
                pendingKey());

        command.onCommand(
                player("Buyer", BUYER_ID, new AtomicReference<>(), new ArrayList<>()),
                null,
                "market",
                new String[0]);

        assertTrue(!javaMenuOpened.get());
    }

    @Test
    void commandListingShowsGeneratedAndCustomNamesWithEnchantmentDetails() {
        MemoryRepository repository = new MemoryRepository();
        repository.create(
                EVENT_ID,
                SELLER_ID,
                "Seller",
                1_000,
                item(
                        material("diamond_axe"),
                        1,
                        Component.text("効率Ⅴ耐久力Ⅲ修繕付きの斧"),
                        axeEnchantments()));
        repository.create(
                UUID.randomUUID(),
                SELLER_ID,
                "Seller",
                1_000,
                item(
                        material("diamond_axe"),
                        1,
                        Component.text("夜伐り"),
                        axeEnchantments()));
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

        command.onCommand(buyer, null, "market", new String[] {"list"});

        assertTrue(messages.stream().anyMatch(message -> message.contains(
                "#1 効率Ⅴ耐久力Ⅲ修繕付きの斧（ダイヤモンドの斧） x1 / 1000 サーバーXP")));
        assertTrue(messages.stream()
                .anyMatch(message -> message.contains(
                        "#2 夜伐り（効率強化 V / 修繕 / 耐久力 III） x1 / 1000 サーバーXP")));
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

    @Test
    void returnedDepartureListingCanBeClaimedFromMarketCommand() {
        MemoryRepository repository = new MemoryRepository();
        MarketListing listing = repository.create(
                EVENT_ID, SELLER_ID, "Seller", 500, item(material("diamond"), 3));
        repository.returnToMailbox(listing.id(), UUID.randomUUID(), SELLER_ID);
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing ignored) {}

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {}
                },
                (player, handler) -> false,
                pendingKey());
        List<String> messages = new ArrayList<>();
        Player seller = player("Seller", SELLER_ID, new AtomicReference<>(), messages);

        command.onCommand(seller, null, "market", new String[] {"claim"});

        assertTrue(repository.pendingClaims(SELLER_ID).isEmpty());
        assertTrue(messages.contains("フリマ返却受取箱から 1 件受け取りました。"));
    }

    @Test
    void claimCompletionFailureRetriesWithoutAddingTheItemTwice() {
        MemoryRepository repository = new MemoryRepository();
        MarketListing listing = repository.create(
                EVENT_ID, SELLER_ID, "Seller", 500, item(material("diamond"), 3));
        repository.returnToMailbox(listing.id(), UUID.randomUUID(), SELLER_ID);
        repository.failNextClaimCompletion.set(true);
        MarketCommand command = new MarketCommand(
                repository,
                new MarketRequestSink() {
                    @Override
                    public void publishListing(MarketListing ignored) {}

                    @Override
                    public void publishRequest(
                            String kind, long listingId, int priceXp, Player player) {}
                },
                (player, handler) -> false,
                (player, handler) -> false,
                pendingKey(),
                claimHistoryKey());
        List<String> messages = new ArrayList<>();
        AtomicInteger additions = new AtomicInteger();
        Player seller = player(
                "Seller", SELLER_ID, new AtomicReference<>(), messages, additions);

        command.onCommand(seller, null, "market", new String[] {"claim"});
        command.onCommand(seller, null, "market", new String[] {"claim"});

        assertEquals(1, additions.get());
        assertTrue(repository.pendingClaims(SELLER_ID).isEmpty());
        assertTrue(messages.stream().anyMatch(message -> message.contains("確定を再試行")));
        assertTrue(messages.contains("フリマ返却受取箱から 1 件受け取りました。"));
    }

    private static Player player(
            String name,
            UUID uniqueId,
            AtomicReference<ItemStack> mainHand,
            List<String> messages) {
        return player(name, uniqueId, mainHand, messages, new AtomicInteger());
    }

    private static Player player(
            String name,
            UUID uniqueId,
            AtomicReference<ItemStack> mainHand,
            List<String> messages,
            AtomicInteger additions) {
        ItemStack[] contents = new ItemStack[36];
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getItemInMainHand" -> mainHand.get();
                    case "setItemInMainHand" -> {
                        mainHand.set(arguments[0] == null ? null : ((ItemStack) arguments[0]).clone());
                        yield null;
                    }
                    case "getStorageContents" -> contents;
                    case "setStorageContents" -> {
                        System.arraycopy(arguments[0], 0, contents, 0, contents.length);
                        yield null;
                    }
                    case "addItem" -> {
                        additions.incrementAndGet();
                        contents[0] = ((ItemStack[]) arguments[0])[0];
                        yield new java.util.HashMap<Integer, ItemStack>();
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        Map<NamespacedKey, String> persistentData = new LinkedHashMap<>();
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> persistentData.get(arguments[0]);
                    case "set" -> {
                        persistentData.put((NamespacedKey) arguments[0], (String) arguments[2]);
                        yield null;
                    }
                    case "remove" -> {
                        persistentData.remove(arguments[0]);
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
        boolean block = material.isBlock();
        String materialKey = material.getKey().getKey();
        return item(
                material,
                amount,
                Component.translatable(
                        (block ? "block.minecraft." : "item.minecraft.") + materialKey));
    }

    private static ItemStack item(Material material, int amount, Component effectiveName) {
        return item(material, amount, effectiveName, Map.of());
    }

    private static ItemStack item(
            Material material,
            int amount,
            Component effectiveName,
            Map<Keyed, Integer> enchantments) {
        ItemStack item = mock(ItemStack.class);
        String materialKey = material.getKey().getKey();
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.serialize()).thenReturn(Map.of("type", materialKey, "amount", amount));
        when(item.effectiveName()).thenReturn(effectiveName);
        stubEnchantments(item, enchantments);
        return item;
    }

    private static Map<Keyed, Integer> axeEnchantments() {
        return Map.of(
                keyed("efficiency"),
                5,
                keyed("unbreaking"),
                3,
                keyed("mending"),
                1);
    }

    private static Keyed keyed(String key) {
        return () -> NamespacedKey.minecraft(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubEnchantments(ItemStack item, Map<Keyed, Integer> enchantments) {
        when(item.getEnchantments()).thenReturn((Map) enchantments);
    }

    @SuppressWarnings("deprecation")
    private static Material material(String key) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.isBlock()).thenReturn(key.equals("ancient_debris"));
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        when(material.translationKey()).thenReturn(
                (key.equals("ancient_debris") ? "block.minecraft." : "item.minecraft.") + key);
        return material;
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey pendingKey() {
        return new NamespacedKey("usapo_event_bridge", "pending_market_escrow");
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey claimHistoryKey() {
        return new NamespacedKey("usapo_event_bridge", "market_transfer_history");
    }

    private static final class MemoryRepository implements MarketRepository {
        private final Map<Long, MarketListing> listings = new LinkedHashMap<>();
        private final Map<UUID, MarketClaim> claims = new LinkedHashMap<>();
        private final AtomicBoolean failNextClaimCompletion = new AtomicBoolean();

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
        public MarketMailboxReturn returnToMailbox(
                long listingId, UUID requestId, UUID sellerId) {
            MarketClaim existing = claims.get(requestId);
            if (existing != null) {
                return new MarketMailboxReturn(listings.get(listingId), true);
            }
            MarketListing current = listings.get(listingId);
            MarketListing cancelled = new MarketListing(
                    current.id(),
                    current.eventId(),
                    current.sellerId(),
                    current.sellerName(),
                    current.priceXp(),
                    current.item(),
                    MarketListing.Status.CANCELLED,
                    requestId,
                    sellerId);
            MarketClaim claim = new MarketClaim(
                    requestId,
                    listingId,
                    sellerId,
                    current.item(),
                    MarketClaim.Status.PENDING,
                    null);
            listings.put(listingId, cancelled);
            claims.put(requestId, claim);
            return new MarketMailboxReturn(cancelled, false);
        }

        @Override
        public List<MarketClaim> pendingClaims(UUID ownerId) {
            return claims.values().stream()
                    .filter(claim -> claim.ownerId().equals(ownerId)
                            && claim.status() != MarketClaim.Status.DELIVERED)
                    .toList();
        }

        @Override
        public MarketClaim prepareClaim(UUID claimId, UUID ownerId, UUID transferId) {
            MarketClaim current = claims.get(claimId);
            MarketClaim changed = new MarketClaim(
                    current.id(),
                    current.listingId(),
                    ownerId,
                    current.item(),
                    MarketClaim.Status.DELIVERING,
                    transferId);
            claims.put(claimId, changed);
            return changed;
        }

        @Override
        public MarketClaim completeClaim(UUID claimId, UUID ownerId, UUID transferId) {
            if (failNextClaimCompletion.getAndSet(false)) {
                throw new IllegalStateException("temporary claim completion failure");
            }
            MarketClaim current = claims.get(claimId);
            MarketClaim changed = new MarketClaim(
                    current.id(),
                    current.listingId(),
                    ownerId,
                    current.item(),
                    MarketClaim.Status.DELIVERED,
                    transferId);
            claims.put(claimId, changed);
            return changed;
        }

        @Override
        public void abortClaim(UUID claimId, UUID ownerId, UUID transferId) {
            MarketClaim current = claims.get(claimId);
            claims.put(claimId, new MarketClaim(
                    current.id(),
                    current.listingId(),
                    ownerId,
                    current.item(),
                    MarketClaim.Status.PENDING,
                    null));
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
