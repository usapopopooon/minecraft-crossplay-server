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

final class ExchangeCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void directCommandsMapToExactAuthoritativeOffers() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicLong now = new AtomicLong(10_000);
        ExchangeCommand command = new ExchangeCommand(
                (selection, player) -> requests.add(selection),
                (player, handler) -> false,
                now::get);
        Player player = player(new ArrayList<>());

        invoke(command, player, "xp", "500");
        now.addAndGet(2_000);
        invoke(command, player, "resource", "diamond", "3");
        now.addAndGet(2_000);
        invoke(command, player, "resource", "emerald", "16");
        now.addAndGet(2_000);
        invoke(command, player, "emerald-diamond", "64");
        now.addAndGet(2_000);
        invoke(command, player, "balance");

        assertEquals(
                List.of(
                        new ExchangeSelection(
                                ExchangeKind.XP,
                                "minecraft:experience",
                                500,
                                100,
                                500,
                                "サーバーXP 100 → Minecraft 500 XP"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:diamond",
                                3,
                                2_160,
                                3,
                                "サーバーXP 2,160 → ダイヤモンド x3"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:emerald",
                                16,
                                360,
                                16,
                                "サーバーXP 360 → エメラルド x16"),
                        new ExchangeSelection(
                                ExchangeKind.EMERALD_DIAMOND,
                                "minecraft:diamond",
                                64,
                                0,
                                2,
                                "エメラルド x64 → ダイヤモンド x2"),
                        ExchangeSelection.balance()),
                requests);
    }

    @Test
    void bedrockFormSelectionUsesTheSameSubmissionPath() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        ExchangeCommand command = new ExchangeCommand(
                (selected, player) -> requests.add(selected),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>());

        assertTrue(command.onCommand(player, null, "exchange", new String[0]));
        selection.get().accept(ExchangeCatalog.XP.getFirst());

        assertEquals(List.of(ExchangeCatalog.XP.getFirst()), requests);
    }

    @Test
    void bedrockFormCannotSubmitAfterPlayerDisconnects() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        ExchangeCommand command = new ExchangeCommand(
                (selected, player) -> requests.add(selected),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>(), false);

        assertTrue(command.onCommand(player, null, "exchange", new String[0]));
        selection.get().accept(ExchangeCatalog.XP.getFirst());

        assertTrue(requests.isEmpty());
    }

    @Test
    void invalidOrRapidRequestsNeverPublishUnexpectedExchange() {
        List<ExchangeSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                (selection, player) -> requests.add(selection),
                (player, handler) -> false,
                () -> 10_000);
        Player player = player(messages);

        invoke(command, player, "resource", "diamond", "2");
        invoke(command, player, "xp", "50");
        invoke(command, player, "xp", "5000");

        assertEquals(List.of(ExchangeCatalog.XP.getFirst()), requests);
        assertTrue(messages.stream().anyMatch(message -> message.contains("処理中")));
    }

    @Test
    void consoleCannotExchangeAndTabCompletionOnlyOffersValidValues() {
        List<ExchangeSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                (selection, player) -> requests.add(selection),
                (player, handler) -> false,
                () -> 10_000);

        assertTrue(command.onCommand(
                sender(messages), null, "exchange", new String[] {"xp", "50"}));

        assertTrue(requests.isEmpty());
        assertTrue(messages.getFirst().contains("プレイヤー"));
        assertEquals(
                List.of("1", "3", "8", "16", "32", "64"),
                command.onTabComplete(
                        player(new ArrayList<>()),
                        null,
                        "exchange",
                        new String[] {"resource", "diamond", ""}));
    }

    private static void invoke(ExchangeCommand command, Player player, String... arguments) {
        assertTrue(command.onCommand(player, null, "exchange", arguments));
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
