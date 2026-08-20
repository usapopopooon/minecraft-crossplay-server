package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

final class QuestActionsTest {
    private static final UUID OWNER =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKER =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCEPT =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID COMPLETE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    @TempDir
    File directory;

    @Test
    void submissionAndClaimsMoveEachItemExactlyOnce() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "quest.yml"));
        QuestListing quest = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:ancient_debris",
                "古代の残骸",
                8,
                24,
                item("diamond", 3),
                1_000);
        repository.accept(quest.id(), ACCEPT, WORKER, "Worker", 2_000);
        List<String> states = new ArrayList<>();
        AtomicInteger broadcasts = new AtomicInteger();
        NamespacedKey pendingSubmission = key("pending_submission");
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> states.add(kind + "|" + changed.id()),
                pendingSubmission,
                changed -> broadcasts.incrementAndGet());
        AtomicReference<ItemStack> workerHand =
                new AtomicReference<>(item("ancient_debris", 10));
        AtomicInteger workerAdditions = new AtomicInteger();
        Player worker = player("Worker", WORKER, workerHand, workerAdditions);

        QuestTransition completed = actions.submit(
                quest.id(), COMPLETE, worker, 3_000);
        QuestTransition duplicate = actions.submit(
                quest.id(), COMPLETE, worker, 4_000);

        assertEquals(QuestListing.Status.COMPLETED, completed.quest().status());
        assertTrue(duplicate.duplicate());
        assertEquals(2, workerHand.get().getAmount());
        assertEquals(List.of("completed|1", "completed|1"), states);
        assertEquals(1, broadcasts.get());
        assertClaim(repository.pendingClaims(OWNER), "ancient_debris", 8);
        assertClaim(repository.pendingClaims(WORKER), "diamond", 3);

        AtomicInteger ownerAdditions = new AtomicInteger();
        Player owner = player("Owner", OWNER, new AtomicReference<>(), ownerAdditions);
        QuestCommand command = new QuestCommand(
                repository,
                actions,
                (target, handler) -> false,
                key("draft"),
                key("pending_reward"),
                key("claim_history"));

        command.onCommand(owner, null, "quest", new String[] {"claim"});
        command.onCommand(worker, null, "quest", new String[] {"claim"});
        command.onCommand(owner, null, "quest", new String[] {"claim"});

        assertEquals(1, ownerAdditions.get());
        assertEquals(1, workerAdditions.get());
        assertTrue(repository.pendingClaims(OWNER).isEmpty());
        assertTrue(repository.pendingClaims(WORKER).isEmpty());
    }

    @Test
    void recoversPersistedCompletionNotificationsExactlyOnce() throws IOException {
        File file = new File(directory, "notification-recovery.yml");
        YamlQuestRepository repository = new YamlQuestRepository(file);
        QuestListing quest = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:ancient_debris",
                "古代の残骸",
                8,
                24,
                item("diamond", 3),
                1_000);
        repository.accept(quest.id(), ACCEPT, WORKER, "Worker", 2_000);
        repository.complete(quest.id(), COMPLETE, WORKER, item("ancient_debris", 8), 3_000);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        ItemStack deserialized = item("diamond", 3);
        when(unsafe.deserializeStack(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(deserialized);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            repository = new YamlQuestRepository(file);
        }
        List<String> states = new ArrayList<>();
        AtomicInteger broadcasts = new AtomicInteger();
        QuestActions recovered = new QuestActions(
                repository,
                (changed, kind) -> states.add(kind + "|" + changed.status()),
                key("pending_submission"),
                changed -> broadcasts.incrementAndGet());

        recovered.recoverPendingNotifications();
        recovered.recoverPendingNotifications();

        assertEquals(List.of("completed|COMPLETED"), states);
        assertEquals(1, broadcasts.get());
        assertTrue(repository.pendingStatePublications().isEmpty());
        assertTrue(repository.pendingCompletionBroadcasts().isEmpty());
    }

    @Test
    void claimSkipsAnItemWithoutSpaceAndExplainsWhatRemains() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "claim-space.yml"));
        ItemStack blockedReward = item("diamond", 5);
        QuestListing blocked = repository.create(
                UUID.randomUUID(),
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                1,
                1,
                blockedReward,
                1_000);
        repository.cancel(blocked.id(), UUID.randomUUID(), OWNER);
        ItemStack deliverableReward = item("emerald", 3);
        QuestListing deliverable = repository.create(
                UUID.randomUUID(),
                OWNER,
                "Owner",
                "minecraft:dirt",
                "土",
                1,
                1,
                deliverableReward,
                2_000);
        repository.cancel(deliverable.id(), UUID.randomUUID(), OWNER);

        ItemStack storedEmeralds = item("emerald", 60);
        when(storedEmeralds.isSimilar(org.mockito.ArgumentMatchers.any(ItemStack.class)))
                .thenAnswer(invocation -> ((ItemStack) invocation.getArgument(0))
                        .getType()
                        .getKey()
                        .getKey()
                        .equals("emerald"));
        ItemStack fullStack = item("stone", 64);
        ItemStack[] contents = new ItemStack[36];
        java.util.Arrays.fill(contents, fullStack);
        contents[0] = storedEmeralds;
        AtomicInteger additions = new AtomicInteger();
        List<String> messages = new ArrayList<>();
        Player owner = player(
                "Owner", OWNER, new AtomicReference<>(), additions, contents, messages);
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> {},
                key("pending_submission"),
                changed -> {});
        QuestCommand command = new QuestCommand(
                repository,
                actions,
                (target, handler) -> false,
                key("draft"),
                key("pending_reward"),
                key("claim_history"));

        command.onCommand(owner, null, "quest", new String[] {"claim"});

        assertEquals(1, additions.get());
        assertClaim(repository.pendingClaims(OWNER), "diamond", 5);
        assertTrue(messages.contains("クエスト受取箱から 1 件受け取りました。"));
        assertTrue(messages.contains("空き不足で受け取れなかったもの: diamond x5"));
        assertTrue(messages.contains("受取箱に残り 1 件です。空きを作って /quest claim を再実行してください。"));
    }

    @Test
    void discardingADraftDoesNotConsumeTheHeldItem() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "discard-draft.yml"));
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> {},
                key("pending_submission"),
                changed -> {});
        AtomicReference<ItemStack> hand = new AtomicReference<>(item("stone", 1));
        List<String> messages = new ArrayList<>();
        Player owner = player(
                "Owner",
                OWNER,
                hand,
                new AtomicInteger(),
                new ItemStack[36],
                messages);
        QuestCommand command = new QuestCommand(
                repository,
                actions,
                (target, handler) -> false,
                key("draft"),
                key("pending_reward"),
                key("claim_history"));

        command.onCommand(owner, null, "quest", new String[] {"create", "1", "1"});
        command.onCommand(owner, null, "quest", new String[] {"discard"});
        command.onCommand(owner, null, "quest", new String[] {"confirm"});

        assertEquals(1, hand.get().getAmount());
        assertTrue(messages.contains("クエストの下書きを破棄しました。アイテムは消費していません。"));
        assertTrue(messages.contains("先に依頼品を手に持って /quest create <個数> <期限時間> を実行してください。"));
        assertTrue(repository.openQuests().isEmpty());
    }

    private static void assertClaim(List<QuestClaim> claims, String key, int amount) {
        assertEquals(1, claims.size());
        assertEquals(key, claims.getFirst().item().getType().getKey().getKey());
        assertEquals(amount, claims.getFirst().item().getAmount());
    }

    private static Player player(
            String name,
            UUID playerId,
            AtomicReference<ItemStack> mainHand,
            AtomicInteger additions) {
        return player(
                name,
                playerId,
                mainHand,
                additions,
                new ItemStack[36],
                new ArrayList<>());
    }

    private static Player player(
            String name,
            UUID playerId,
            AtomicReference<ItemStack> mainHand,
            AtomicInteger additions,
            ItemStack[] contents,
            List<String> messages) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getItemInMainHand" -> mainHand.get();
                    case "setItemInMainHand" -> {
                        mainHand.set(arguments[0] == null ? null : (ItemStack) arguments[0]);
                        yield null;
                    }
                    case "getStorageContents" -> contents;
                    case "setStorageContents" -> null;
                    case "addItem" -> {
                        additions.incrementAndGet();
                        yield new HashMap<Integer, ItemStack>();
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        Map<String, String> values = new LinkedHashMap<>();
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> values.get(arguments[0].toString());
                    case "set" -> {
                        values.put(arguments[0].toString(), (String) arguments[2]);
                        yield null;
                    }
                    case "remove" -> {
                        values.remove(arguments[0].toString());
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> playerId;
                    case "getInventory" -> inventory;
                    case "getPersistentDataContainer" -> data;
                    case "isOnline" -> true;
                    case "sendMessage" -> {
                        if (arguments != null && arguments.length > 0 && arguments[0] instanceof String message) {
                            messages.add(message);
                        }
                        yield null;
                    }
                    case "saveData" -> null;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(String key, int initialAmount) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return item(material, key, initialAmount);
    }

    private static ItemStack item(Material material, String key, int initialAmount) {
        AtomicInteger amount = new AtomicInteger(initialAmount);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenAnswer(ignored -> item(material, key, amount.get()));
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenAnswer(ignored -> amount.get());
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.hasItemMeta()).thenReturn(false);
        when(item.serialize()).thenAnswer(ignored ->
                Map.of("schema_version", 1, "type", key, "amount", amount.get()));
        doAnswer(invocation -> {
                    amount.set(invocation.getArgument(0));
                    return null;
                })
                .when(item)
                .setAmount(org.mockito.ArgumentMatchers.anyInt());
        return item;
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey key(String value) {
        return new NamespacedKey("usapo_event_bridge", value);
    }
}
