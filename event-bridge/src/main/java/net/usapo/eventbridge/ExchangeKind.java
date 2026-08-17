package net.usapo.eventbridge;

enum ExchangeKind {
    BALANCE("balance"),
    XP("xp"),
    RESOURCE("resource"),
    EMERALD_DIAMOND("emerald_diamond");

    private final String wireName;

    ExchangeKind(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }
}
