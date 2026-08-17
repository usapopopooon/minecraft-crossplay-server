package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class ExchangeRequestPublisher implements ExchangeRequestSink {
    static final String PREFIX = "USAPO_EXCHANGE_REQUEST|1|";

    private final Consumer<String> logSink;
    private final Clock clock;
    private final Supplier<UUID> requestIds;

    ExchangeRequestPublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC(), UUID::randomUUID);
    }

    ExchangeRequestPublisher(
            Consumer<String> logSink,
            Clock clock,
            Supplier<UUID> requestIds) {
        this.logSink = logSink;
        this.clock = clock;
        this.requestIds = requestIds;
    }

    @Override
    public void publish(ExchangeSelection selection, Player player) {
        String encodedName = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(player.getName().getBytes(StandardCharsets.UTF_8));
        logSink.accept(PREFIX
                + requestIds.get()
                + "|"
                + player.getUniqueId()
                + "|"
                + encodedName
                + "|"
                + selection.kind().wireName()
                + "|"
                + selection.target()
                + "|"
                + selection.amount()
                + "|"
                + selection.expectedCostXp()
                + "|"
                + selection.expectedReward()
                + "|"
                + clock.instant().toEpochMilli());
    }
}
