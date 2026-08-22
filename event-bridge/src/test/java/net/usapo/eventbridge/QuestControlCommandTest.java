package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuestControlCommandTest {
    private static final UUID OWNER =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKER =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCEPT =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ABANDON =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    @TempDir
    File directory;

    @Test
    void rconWiringKeepsWorkerNameUuidRequestAndOldRetryIdempotent() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "quest.yml"));
        QuestListing quest = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                32,
                24,
                item("diamond", 3),
                System.currentTimeMillis());
        List<String> states = new ArrayList<>();
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> states.add(kind + "|" + changed.status()),
                key("pending_submission"),
                changed -> {});
        QuestControlCommand control =
                new QuestControlCommand(actions, repository, ignored -> null);
        List<String> responses = new ArrayList<>();
        CommandSender sender = sender(responses);
        String[] accept = {
            "quest-accept",
            Long.toString(quest.id()),
            WORKER.toString(),
            encoded(".Worker"),
            ACCEPT.toString()
        };

        control.onCommand(sender, null, "usapo-event-bridge", accept);
        control.onCommand(
                sender,
                null,
                "usapo-event-bridge",
                new String[] {
                    "quest-abandon",
                    Long.toString(quest.id()),
                    WORKER.toString(),
                    ABANDON.toString()
                });
        control.onCommand(
                sender,
                null,
                "usapo-event-bridge",
                new String[] {
                    "quest-abandon",
                    Long.toString(quest.id()),
                    WORKER.toString(),
                    UUID.fromString("77777777-7777-4777-8777-777777777777").toString()
                });
        control.onCommand(sender, null, "usapo-event-bridge", accept);

        QuestListing current = repository.find(quest.id()).orElseThrow();
        assertEquals(QuestListing.Status.OPEN, current.status());
        assertEquals(null, current.workerId());
        assertEquals(
                List.of(
                        "accepted|ACCEPTED",
                        "abandoned|OPEN",
                        "snapshot|OPEN",
                        "snapshot|OPEN"),
                states);
        assertTrue(responses.getFirst().endsWith("|completed|accepted|new"));
        assertTrue(responses.get(1).endsWith("|completed|open|new"));
        assertTrue(responses.get(2).endsWith("|completed|open|duplicate"));
        assertTrue(responses.getLast().endsWith("|completed|open|duplicate"));
    }

    @Test
    void internalInvalidationCancelsAcceptedUnlinkedOwnerQuest() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "invalidate.yml"));
        QuestListing quest = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                32,
                24,
                item("diamond", 3),
                System.currentTimeMillis());
        repository.accept(quest.id(), ACCEPT, WORKER, "Worker", System.currentTimeMillis());
        List<String> states = new ArrayList<>();
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> states.add(kind + "|" + changed.status()),
                key("pending_submission"),
                changed -> {});
        QuestControlCommand control =
                new QuestControlCommand(actions, repository, ignored -> null);
        List<String> responses = new ArrayList<>();
        UUID request = UUID.fromString("88888888-8888-4888-8888-888888888888");

        control.onCommand(
                sender(responses),
                null,
                "usapo-event-bridge",
                new String[] {
                    "quest-invalidate",
                    Long.toString(quest.id()),
                    OWNER.toString(),
                    request.toString()
                });

        QuestListing current = repository.find(quest.id()).orElseThrow();
        assertEquals(QuestListing.Status.CANCELLED, current.status());
        assertEquals(List.of("invalidated|CANCELLED"), states);
        assertTrue(responses.getFirst().endsWith("|completed|cancelled|new"));
        assertEquals(1, repository.pendingClaims(OWNER).size());
    }

    @Test
    void adminCreateBuildsAnIdempotentSystemQuestWithoutPlayerInventory() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "admin-create.yml"));
        List<String> states = new ArrayList<>();
        QuestActions actions = new QuestActions(
                repository,
                (changed, kind) -> states.add(kind + "|" + changed.status()),
                key("pending_submission"),
                changed -> {});
        ItemStack stone = mutableItem("stone", "石", 64);
        ItemStack diamond = mutableItem("diamond", "ダイヤモンド", 64);
        QuestControlCommand control = new QuestControlCommand(
                actions,
                repository,
                ignored -> null,
                itemId -> itemId.equals("minecraft:stone") ? stone : diamond);
        List<String> responses = new ArrayList<>();
        String[] arguments = {
            "quest-admin-create",
            "minecraft:stone",
            "32",
            "minecraft:diamond",
            "3",
            "24",
            EVENT.toString()
        };

        control.onCommand(sender(responses), null, "usapo-event-bridge", arguments);
        control.onCommand(sender(responses), null, "usapo-event-bridge", arguments);

        QuestListing quest = repository.find(1).orElseThrow();
        assertEquals(QuestIssuer.SYSTEM_ID, quest.ownerId());
        assertEquals(QuestIssuer.SYSTEM_NAME, quest.ownerName());
        assertEquals("minecraft:stone", quest.requestedItemId());
        assertEquals(32, quest.requestedCount());
        assertEquals("diamond", quest.reward().getType().getKey().getKey());
        assertEquals(3, quest.reward().getAmount());
        assertEquals(List.of("created|OPEN", "created|OPEN"), states);
        assertTrue(responses.getFirst().endsWith("|1|completed|new"));
        assertTrue(responses.getLast().endsWith("|1|completed|duplicate"));
    }

    @Test
    void adminCreateRejectsAnUnknownOrOversizedItem() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "admin-invalid.yml"));
        QuestActions actions = new QuestActions(
                repository, (changed, kind) -> {}, key("pending_submission"), changed -> {});
        ItemStack stone = mutableItem("stone", "石", 64);
        QuestControlCommand control = new QuestControlCommand(
                actions,
                repository,
                ignored -> null,
                itemId -> itemId.equals("minecraft:stone") ? stone : null);
        List<String> responses = new ArrayList<>();

        control.onCommand(
                sender(responses),
                null,
                "usapo-event-bridge",
                new String[] {
                    "quest-admin-create",
                    "minecraft:stone",
                    "65",
                    "minecraft:missing",
                    "1",
                    "24",
                    EVENT.toString()
                });

        assertTrue(repository.openQuests().isEmpty());
        assertTrue(responses.getFirst().endsWith("|0|invalid_requested_count|new"));
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

    @SuppressWarnings("deprecation")
    private static ItemStack item(String key, int amount) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.hasItemMeta()).thenReturn(false);
        when(item.serialize()).thenReturn(Map.of("type", key, "amount", amount));
        return item;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack mutableItem(String key, String name, int maxStackSize) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.isItem()).thenReturn(true);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        when(material.translationKey()).thenReturn("item.minecraft." + key);
        AtomicInteger amount = new AtomicInteger(1);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenAnswer(ignored -> amount.get());
        when(item.getMaxStackSize()).thenReturn(maxStackSize);
        when(item.hasItemMeta()).thenReturn(false);
        when(item.effectiveName()).thenReturn(net.kyori.adventure.text.Component.text(name));
        org.mockito.Mockito.doAnswer(invocation -> {
                    amount.set(invocation.getArgument(0));
                    return null;
                })
                .when(item)
                .setAmount(org.mockito.ArgumentMatchers.anyInt());
        when(item.serialize()).thenReturn(Map.of("type", key));
        return item;
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("deprecation")
    private static NamespacedKey key(String value) {
        return new NamespacedKey("usapo_event_bridge", value);
    }
}
