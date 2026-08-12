package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

final class EmeraldExchangePublisher {
    static final String PREFIX = "USAPO_EMERALD_EXCHANGE|1|";

    private final Consumer<String> logSink;
    private final Clock clock;

    EmeraldExchangePublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC());
    }

    EmeraldExchangePublisher(Consumer<String> logSink, Clock clock) {
        this.logSink = logSink;
        this.clock = clock;
    }

    void publish(UUID requestId, Player player, int emeraldCount, int diamondCount) {
        String encodedName = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(player.getName().getBytes(StandardCharsets.UTF_8));
        logSink.accept(PREFIX
                + requestId
                + "|"
                + player.getUniqueId()
                + "|"
                + encodedName
                + "|"
                + emeraldCount
                + "|"
                + diamondCount
                + "|"
                + clock.instant().toEpochMilli());
    }
}
