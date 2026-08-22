package net.usapo.eventbridge;

record ExchangeSelection(
        ExchangeKind kind,
        String target,
        String targetName,
        int amount,
        int expectedCostXp,
        int expectedReward,
        String description) {
    static ExchangeSelection balance() {
        return new ExchangeSelection(
                ExchangeKind.BALANCE, "balance", "XP残高", 0, 0, 0, "XP残高");
    }
}
