package net.usapo.eventbridge;

import java.util.UUID;

final class QuestIssuer {
    static final UUID SYSTEM_ID = new UUID(0, 0);
    static final String SYSTEM_NAME = "-";

    private QuestIssuer() {}

    static boolean isSystem(QuestListing quest) {
        return SYSTEM_ID.equals(quest.ownerId());
    }
}
