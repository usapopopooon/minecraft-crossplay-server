package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class ItemGachaRequestPublisher implements ItemGachaRequestSink {
    static final String PREFIX = "USAPO_ITEM_GACHA_REQUEST|3|";

    private final Consumer<String> logSink;
    private final Clock clock;
    private final Supplier<UUID> requestIds;

    ItemGachaRequestPublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC(), UUID::randomUUID);
    }

    ItemGachaRequestPublisher(
            Consumer<String> logSink,
            Clock clock,
            Supplier<UUID> requestIds) {
        this.logSink = logSink;
        this.clock = clock;
        this.requestIds = requestIds;
    }

    @Override
    public void publish(ItemGachaCategory category, ItemGachaKind kind, Player player) {
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
                + category.wireName()
                + "|"
                + kind.wireName()
                + "|"
                + kind.costXp()
                + "|"
                + clock.instant().toEpochMilli());
    }
}
