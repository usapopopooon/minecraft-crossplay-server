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

final class EmeraldExchangePublisherTest {
    @Test
    void publishesVersionedUuidBasedAuditEvent() {
        List<String> messages = new ArrayList<>();
        UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID playerId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        Player player = player(".Yuki1991", playerId);
        EmeraldExchangePublisher publisher = new EmeraldExchangePublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));

        publisher.publish(requestId, player, 32, 1);

        assertEquals(
                List.of("USAPO_EMERALD_EXCHANGE|2|" + requestId + "|" + playerId
                        + "|Lll1a2kxOTkx|32|1|1786406400000"),
                messages);
    }

    private static Player player(String name, UUID uniqueId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uniqueId;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }
}
