package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class ExperienceAccumulatorTest {
    @Test
    void batchesEachPlayersExperienceWithoutMixingPlayers() {
        List<Published> published = new ArrayList<>();
        ExperienceAccumulator accumulator = new ExperienceAccumulator(
                (kind, player, amount) -> published.add(
                        new Published(kind, player.getUniqueId(), amount)));
        Player steve = player("11111111-1111-1111-1111-111111111111");
        Player alex = player("22222222-2222-2222-2222-222222222222");

        accumulator.add(steve, 3);
        accumulator.add(alex, 5);
        accumulator.add(steve, 7);
        accumulator.flushAll();

        assertEquals(2, published.size());
        assertEquals(
                10,
                published.stream()
                        .filter(event -> event.playerId().equals(steve.getUniqueId()))
                        .findFirst()
                        .orElseThrow()
                        .amount());
        assertEquals(
                5,
                published.stream()
                        .filter(event -> event.playerId().equals(alex.getUniqueId()))
                        .findFirst()
                        .orElseThrow()
                        .amount());
    }

    @Test
    void flushingDepartingPlayerKeepsOtherPlayersPendingExperience() {
        List<Published> published = new ArrayList<>();
        ExperienceAccumulator accumulator = new ExperienceAccumulator(
                (kind, player, amount) -> published.add(
                        new Published(kind, player.getUniqueId(), amount)));
        Player steve = player("11111111-1111-1111-1111-111111111111");
        Player alex = player("22222222-2222-2222-2222-222222222222");
        accumulator.add(steve, 3);
        accumulator.add(alex, 5);

        accumulator.flush(steve);

        assertEquals(List.of(new Published(ActivityKind.EXPERIENCE, steve.getUniqueId(), 3)), published);

        accumulator.flushAll();

        assertEquals(
                List.of(
                        new Published(ActivityKind.EXPERIENCE, steve.getUniqueId(), 3),
                        new Published(ActivityKind.EXPERIENCE, alex.getUniqueId(), 5)),
                published);
    }

    private static Player player(String uniqueId) {
        UUID id = UUID.fromString(uniqueId);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> method.getName().equals("getUniqueId")
                        ? id
                        : EventLogPublisherTest.defaultValue(method.getReturnType()));
    }

    private record Published(ActivityKind kind, UUID playerId, int amount) {}
}
