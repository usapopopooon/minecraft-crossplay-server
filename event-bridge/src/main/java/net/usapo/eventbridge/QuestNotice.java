package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;

record QuestNotice(UUID id, long questId, UUID playerId, String message) {
    QuestNotice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(message, "message");
        if (questId <= 0 || message.isBlank()) {
            throw new IllegalArgumentException("invalid quest notice");
        }
    }
}
