package net.usapo.eventbridge;

enum ExchangeKind {
    BALANCE("balance"),
    XP("xp"),
    RESOURCE("resource"),
    EMERALD_DIAMOND("emerald_diamond"),
    DIAMOND_EMERALD("diamond_emerald"),
    MATERIAL_BUYBACK("material_buyback");

    private final String wireName;

    ExchangeKind(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }
}
