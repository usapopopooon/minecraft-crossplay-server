package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;

record QuestStatePublication(QuestListing quest, UUID transitionId, String transitionKind) {
    QuestStatePublication {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(transitionKind, "transitionKind");
        if (!quest.lastTransitionId().equals(transitionId) || transitionKind.isBlank()) {
            throw new IllegalArgumentException("invalid pending quest publication");
        }
    }
}
