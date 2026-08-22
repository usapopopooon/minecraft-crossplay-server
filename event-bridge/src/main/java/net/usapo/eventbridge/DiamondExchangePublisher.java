package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

final class DiamondExchangePublisher {
    static final String PREFIX = "USAPO_DIAMOND_EXCHANGE|1|";

    private final Consumer<String> logSink;
    private final Clock clock;

    DiamondExchangePublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC());
    }

    DiamondExchangePublisher(Consumer<String> logSink, Clock clock) {
        this.logSink = logSink;
        this.clock = clock;
    }

    void publish(UUID requestId, Player player, int diamondCount, int emeraldCount) {
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
                + diamondCount
                + "|"
                + emeraldCount
                + "|"
                + clock.instant().toEpochMilli());
    }
}
