package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class MaterialBuybackCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void releaseCommandMapsPlayerAndRequestWithoutAllowingAStaleRequestToUnlock() {
        MaterialBuybackPendingRegistry registry = new MaterialBuybackPendingRegistry();
        registry.register(PLAYER_ID, REQUEST_ID);
        MaterialBuybackCommand command = new MaterialBuybackCommand(
                playerId -> null,
                new NamespacedKey("usapo", "material_buyback_test"),
                new MaterialBuybackExchange(),
                registry,
                Logger.getLogger("MaterialBuybackCommandTest"));
        List<String> messages = new ArrayList<>();
        CommandSender sender = sender(messages);
        UUID staleRequest = UUID.randomUUID();

        command.onCommand(sender, null, "usapo-event-bridge", new String[] {
            "material-buyback-release", PLAYER_ID.toString(), staleRequest.toString()
        });
        command.onCommand(sender, null, "usapo-event-bridge", new String[] {
            "material-buyback-release", PLAYER_ID.toString(), REQUEST_ID.toString()
        });

        assertEquals(
                List.of(
                        MaterialBuybackCommand.RELEASE_RESULT_PREFIX + staleRequest + "|"
                                + PLAYER_ID + "|request_mismatch",
                        MaterialBuybackCommand.RELEASE_RESULT_PREFIX + REQUEST_ID + "|"
                                + PLAYER_ID + "|released"),
                messages);
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
