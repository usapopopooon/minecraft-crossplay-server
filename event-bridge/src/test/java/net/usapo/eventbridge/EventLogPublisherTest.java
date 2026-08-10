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

final class EventLogPublisherTest {
    @Test
    void publishesVersionedUuidBasedEventWithEncodedPlayerName() {
        List<String> messages = new ArrayList<>();
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Player player = player(".Yuki1991", playerId);
        EventLogPublisher publisher = new EventLogPublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
                () -> eventId);

        publisher.publish(ActivityKind.FISHING, player, 1);

        assertEquals(
                List.of("USAPO_ACTIVITY|1|11111111-1111-1111-1111-111111111111"
                        + "|fishing|22222222-2222-2222-2222-222222222222"
                        + "|Lll1a2kxOTkx|1|1786406400000"),
                messages);
    }

    private static Player player(String name, UUID uniqueId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uniqueId;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
