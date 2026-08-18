package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class ItemGachaCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void directCommandsKeepLegacyAliasesAndMapCategoryCommandsExactly() {
        List<ItemGachaSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        AtomicLong now = new AtomicLong(10_000);
        Player player = player(messages);
        ItemGachaCommand command =
                new ItemGachaCommand(
                        (category, kind, found) -> requests.add(
                                new ItemGachaSelection(category, kind)),
                        (found, handler) -> false,
                        now::get);

        assertTrue(command.onCommand(player, null, "gacha", new String[] {"normal"}));
        now.addAndGet(2_000);
        assertTrue(command.onCommand(player, null, "gacha", new String[] {"rare"}));
        now.addAndGet(2_000);
        assertTrue(command.onCommand(
                player, null, "gacha", new String[] {"resource", "normal"}));
        now.addAndGet(2_000);
        assertTrue(command.onCommand(
                player, null, "gacha", new String[] {"equipment", "rare"}));

        assertEquals(
                List.of(
                        new ItemGachaSelection(ItemGachaCategory.ALL, ItemGachaKind.NORMAL),
                        new ItemGachaSelection(ItemGachaCategory.ALL, ItemGachaKind.PREMIUM),
                        new ItemGachaSelection(ItemGachaCategory.RESOURCES, ItemGachaKind.NORMAL),
                        new ItemGachaSelection(ItemGachaCategory.EQUIPMENT, ItemGachaKind.PREMIUM)),
                requests);
        assertEquals(4, messages.stream().filter(message -> message.contains("受け付けました")).count());
    }

    @Test
    void bedrockFormSelectionUsesTheSameSubmissionPath() {
        List<ItemGachaSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ItemGachaSelection>> selection = new AtomicReference<>();
        ItemGachaCommand command = new ItemGachaCommand(
                (category, kind, player) -> requests.add(new ItemGachaSelection(category, kind)),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>());

        assertTrue(command.onCommand(player, null, "gacha", new String[0]));
        selection.get().accept(
                new ItemGachaSelection(ItemGachaCategory.ADVENTURE, ItemGachaKind.PREMIUM));

        assertEquals(
                List.of(new ItemGachaSelection(
                        ItemGachaCategory.ADVENTURE, ItemGachaKind.PREMIUM)),
                requests);
    }

    @Test
    void bedrockFormCannotSubmitAfterPlayerDisconnects() {
        List<ItemGachaSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ItemGachaSelection>> selection = new AtomicReference<>();
        ItemGachaCommand command = new ItemGachaCommand(
                (category, kind, player) -> requests.add(new ItemGachaSelection(category, kind)),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>(), false);

        assertTrue(command.onCommand(player, null, "gacha", new String[0]));
        selection.get().accept(
                new ItemGachaSelection(ItemGachaCategory.ALL, ItemGachaKind.PREMIUM));

        assertTrue(requests.isEmpty());
    }

    @Test
    void duplicateTapIsDebouncedWithoutPublishingTwice() {
        List<ItemGachaSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ItemGachaCommand command = new ItemGachaCommand(
                (category, kind, player) -> requests.add(new ItemGachaSelection(category, kind)),
                (player, handler) -> false,
                () -> 10_000);
        Player player = player(messages);

        command.onCommand(player, null, "gacha", new String[] {"normal"});
        command.onCommand(player, null, "gacha", new String[] {"rare"});

        assertEquals(
                List.of(new ItemGachaSelection(ItemGachaCategory.ALL, ItemGachaKind.NORMAL)),
                requests);
        assertTrue(messages.stream().anyMatch(message -> message.contains("処理中")));
    }

    @Test
    void consoleAndInvalidArgumentsNeverPublish() {
        List<ItemGachaSelection> requests = new ArrayList<>();
        List<String> consoleMessages = new ArrayList<>();
        ItemGachaCommand command = new ItemGachaCommand(
                (category, kind, player) -> requests.add(new ItemGachaSelection(category, kind)),
                (player, handler) -> false,
                () -> 10_000);

        assertTrue(command.onCommand(
                sender(consoleMessages), null, "gacha", new String[] {"normal"}));
        assertTrue(command.onCommand(
                player(new ArrayList<>()), null, "gacha", new String[] {"unknown"}));

        assertTrue(requests.isEmpty());
        assertTrue(consoleMessages.getFirst().contains("プレイヤー"));
    }

    private static Player player(List<String> messages) {
        return player(messages, true);
    }

    private static Player player(List<String> messages, boolean online) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "Steve";
                    case "getUniqueId" -> PLAYER_ID;
                    case "isOnline" -> online;
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

    private static CommandSender sender(List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof String message) {
                        messages.add(message);
                    }
                    return EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }
}
