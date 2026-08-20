package net.usapo.eventbridge;

record QuestFormAction(Kind kind, long questId, int count, int hours) {
    enum Kind {
        LIST,
        CREATE,
        CONFIRM,
        DISCARD,
        MINE,
        ACCEPT,
        SUBMIT,
        ABANDON,
        CANCEL,
        CLAIM
    }
}
