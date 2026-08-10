package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class EventLogPublisher implements ActivityPublisher {
    static final String PREFIX = "USAPO_ACTIVITY|1|";

    private final Consumer<String> logSink;
    private final Clock clock;
    private final Supplier<UUID> eventIds;

    EventLogPublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC(), UUID::randomUUID);
    }

    EventLogPublisher(Consumer<String> logSink, Clock clock, Supplier<UUID> eventIds) {
        this.logSink = logSink;
        this.clock = clock;
        this.eventIds = eventIds;
    }

    @Override
    public void publish(ActivityKind kind, Player player, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String encodedName = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(player.getName().getBytes(StandardCharsets.UTF_8));
        logSink.accept(PREFIX
                + eventIds.get()
                + "|"
                + kind.wireName()
                + "|"
                + player.getUniqueId()
                + "|"
                + encodedName
                + "|"
                + amount
                + "|"
                + clock.instant().toEpochMilli());
    }
}
