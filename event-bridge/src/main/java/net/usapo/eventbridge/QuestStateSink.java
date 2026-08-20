package net.usapo.eventbridge;

interface QuestStateSink {
    void publish(QuestListing quest, String transitionKind);
}
