package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class EmeraldDiamondCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void mapsPlayerRequestAndCountToOneExchangeAndNotification() {
        List<String> messages = new ArrayList<>();
        CommandSender sender = sender(messages);
        Player player = player();
        AtomicReference<String> exchangeArguments = new AtomicReference<>();
        AtomicReference<String> notificationArguments = new AtomicReference<>();
        EmeraldDiamondCommand command = new EmeraldDiamondCommand(
                playerId -> playerId.equals(PLAYER_ID) ? player : null,
                (foundPlayer, requestId, emeraldCount) -> {
                    exchangeArguments.set(
                            foundPlayer.getName() + "|" + requestId + "|" + emeraldCount);
                    return new EmeraldDiamondExchange.Result(
                            EmeraldDiamondExchange.Status.COMPLETED, emeraldCount, 1, false);
                },
                (requestId, foundPlayer, emeraldCount, diamondCount) -> notificationArguments.set(
                        requestId + "|" + foundPlayer.getName() + "|" + emeraldCount + "|"
                                + diamondCount));

        assertTrue(command.onCommand(
                sender,
                null,
                "usapo-event-bridge",
                new String[] {
                    "emerald-diamond-v2", PLAYER_ID.toString(), "32", REQUEST_ID.toString()
                }));

        assertEquals("Steve|" + REQUEST_ID + "|32", exchangeArguments.get());
        assertEquals(REQUEST_ID + "|Steve|32|1", notificationArguments.get());
        assertEquals(
                List.of("USAPO_EMERALD_EXCHANGE_RESULT|2|" + REQUEST_ID
                        + "|completed|32|1|new"),
                messages);
    }

    @Test
    void reportsOfflineWithoutCallingExchange() {
        List<String> messages = new ArrayList<>();
        EmeraldDiamondCommand command = new EmeraldDiamondCommand(
                playerId -> null,
                (player, requestId, emeraldCount) -> {
                    throw new AssertionError("exchange must not run");
                },
                (requestId, player, emeraldCount, diamondCount) -> {
                    throw new AssertionError("notification must not run");
                });

        assertTrue(command.onCommand(
                sender(messages),
                null,
                "usapo-event-bridge",
                new String[] {
                    "emerald-diamond-v2", PLAYER_ID.toString(), "32", REQUEST_ID.toString()
                }));

        assertEquals(
                List.of("USAPO_EMERALD_EXCHANGE_RESULT|2|" + REQUEST_ID
                        + "|player_offline|32|1|new"),
                messages);
    }

    @Test
    void rejectsLegacyRateCommandInsteadOfApplyingTheOldRate() {
        List<String> messages = new ArrayList<>();
        EmeraldDiamondCommand command = new EmeraldDiamondCommand(
                playerId -> {
                    throw new AssertionError("legacy command must not look up a player");
                },
                (player, requestId, emeraldCount) -> {
                    throw new AssertionError("legacy command must not exchange items");
                },
                (requestId, player, emeraldCount, diamondCount) -> {
                    throw new AssertionError("legacy command must not notify");
                });

        assertFalse(command.onCommand(
                sender(messages),
                null,
                "usapo-event-bridge",
                new String[] {
                    "emerald-diamond", PLAYER_ID.toString(), "16", REQUEST_ID.toString()
                }));
        assertTrue(messages.isEmpty());
    }

    @Test
    void duplicateSuccessDoesNotPublishTwice() {
        List<String> messages = new ArrayList<>();
        EmeraldDiamondCommand command = new EmeraldDiamondCommand(
                playerId -> player(),
                (player, requestId, emeraldCount) -> new EmeraldDiamondExchange.Result(
                        EmeraldDiamondExchange.Status.COMPLETED, emeraldCount, 1, true),
                (requestId, player, emeraldCount, diamondCount) -> {
                    throw new AssertionError("duplicate must not notify");
                });

        assertTrue(command.onCommand(
                sender(messages),
                null,
                "usapo-event-bridge",
                new String[] {
                    "emerald-diamond-v2", PLAYER_ID.toString(), "32", REQUEST_ID.toString()
                }));

        assertEquals(
                List.of("USAPO_EMERALD_EXCHANGE_RESULT|2|" + REQUEST_ID
                        + "|completed|32|1|duplicate"),
                messages);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> method.getName().equals("getName")
                        ? "Steve"
                        : EventLogPublisherTest.defaultValue(method.getReturnType()));
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
