package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class QuestStatePublisher implements QuestStateSink {
    static final String PREFIX = "USAPO_QUEST_STATE|1|";
    private static final Set<String> TRANSITION_KINDS = Set.of(
            "created", "accepted", "abandoned", "reopened", "completed", "cancelled",
            "expired", "invalidated", "snapshot");

    private final Consumer<String> logSink;
    private final Clock clock;

    QuestStatePublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC());
    }

    QuestStatePublisher(Consumer<String> logSink, Clock clock) {
        this.logSink = logSink;
        this.clock = clock;
    }

    @Override
    public void publish(QuestListing quest, String transitionKind) {
        if (!TRANSITION_KINDS.contains(transitionKind)) {
            throw new IllegalArgumentException("invalid quest transition kind");
        }
        logSink.accept(PREFIX
                + quest.lastTransitionId()
                + "|"
                + transitionKind
                + "|"
                + quest.id()
                + "|"
                + quest.eventId()
                + "|"
                + quest.ownerId()
                + "|"
                + encode(quest.ownerName())
                + "|"
                + optionalUuid(quest.workerId())
                + "|"
                + optionalText(quest.workerName())
                + "|"
                + encode(quest.requestedItemId())
                + "|"
                + encode(quest.requestedItemName())
                + "|"
                + quest.requestedCount()
                + "|"
                + encode(quest.reward().getType().getKey().toString())
                + "|"
                + encode(MarketItems.questDisplayName(quest.reward()))
                + "|"
                + quest.reward().getAmount()
                + "|"
                + quest.fulfillmentHours()
                + "|"
                + quest.status().name().toLowerCase()
                + "|"
                + quest.openExpiresAtMillis()
                + "|"
                + quest.acceptedDeadlineMillis()
                + "|"
                + quest.createdAtMillis()
                + "|"
                + clock.instant().toEpochMilli());
    }

    static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String optionalUuid(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private static String optionalText(String value) {
        return value == null ? "-" : encode(value);
    }
}
