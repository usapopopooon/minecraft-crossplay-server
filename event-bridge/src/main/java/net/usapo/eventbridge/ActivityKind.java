package net.usapo.eventbridge;

enum ActivityKind {
    FISHING("fishing"),
    WOODCUTTING("woodcutting"),
    WOODCUTTING_RESET("woodcutting_reset"),
    EXPERIENCE("experience");

    private final String wireName;

    ActivityKind(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }
}
