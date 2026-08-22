package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class DiamondEmeraldCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void mapsExactDiamondCountToExchangeAndNotification() {
        List<String> messages = new ArrayList<>();
        Player player = player();
        AtomicReference<String> exchangeArguments = new AtomicReference<>();
        AtomicReference<String> notificationArguments = new AtomicReference<>();
        DiamondEmeraldCommand command = new DiamondEmeraldCommand(
                playerId -> playerId.equals(PLAYER_ID) ? player : null,
                (foundPlayer, requestId, diamondCount) -> {
                    exchangeArguments.set(
                            foundPlayer.getName() + "|" + requestId + "|" + diamondCount);
                    return new EmeraldDiamondExchange.Result(
                            EmeraldDiamondExchange.Status.COMPLETED, 64, diamondCount, false);
                },
                (requestId, foundPlayer, diamondCount, emeraldCount) -> notificationArguments.set(
                        requestId + "|" + foundPlayer.getName() + "|" + diamondCount + "|"
                                + emeraldCount));

        assertTrue(command.onCommand(
                sender(messages),
                null,
                "usapo-event-bridge",
                new String[] {
                    "diamond-emerald-v1", PLAYER_ID.toString(), "4", REQUEST_ID.toString()
                }));

        assertEquals("Steve|" + REQUEST_ID + "|4", exchangeArguments.get());
        assertEquals(REQUEST_ID + "|Steve|4|64", notificationArguments.get());
        assertEquals(
                List.of("USAPO_DIAMOND_EXCHANGE_RESULT|1|" + REQUEST_ID
                        + "|completed|4|64|new"),
                messages);
    }

    @Test
    void reportsOfflineWithoutMutatingItems() {
        List<String> messages = new ArrayList<>();
        DiamondEmeraldCommand command = new DiamondEmeraldCommand(
                ignored -> null,
                (player, requestId, count) -> {
                    throw new AssertionError("offline exchange must not run");
                },
                (requestId, player, diamonds, emeralds) -> {
                    throw new AssertionError("offline exchange must not notify");
                });

        assertTrue(command.onCommand(
                sender(messages),
                null,
                "usapo-event-bridge",
                new String[] {
                    "diamond-emerald-v1", PLAYER_ID.toString(), "1", REQUEST_ID.toString()
                }));

        assertEquals(
                List.of("USAPO_DIAMOND_EXCHANGE_RESULT|1|" + REQUEST_ID
                        + "|player_offline|1|16|new"),
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
