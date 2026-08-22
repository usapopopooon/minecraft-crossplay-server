package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class DiamondExchangePublisherTest {
    @Test
    void publishesBoundDirectionCountsIdentityAndTimestamp() {
        List<String> logs = new ArrayList<>();
        UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID playerId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        DiamondExchangePublisher publisher = new DiamondExchangePublisher(
                logs::add,
                Clock.fixed(Instant.ofEpochMilli(1_786_406_400_000L), ZoneOffset.UTC));

        publisher.publish(requestId, player(playerId), 4, 64);

        assertEquals(
                List.of("USAPO_DIAMOND_EXCHANGE|1|" + requestId + "|" + playerId
                        + "|U3RldmU|4|64|1786406400000"),
                logs);
    }

    private static Player player(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "Steve";
                    case "getUniqueId" -> playerId;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }
}
