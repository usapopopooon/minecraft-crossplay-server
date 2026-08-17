package net.usapo.eventbridge;

record ExchangeSelection(
        ExchangeKind kind,
        String target,
        int amount,
        int expectedCostXp,
        int expectedReward,
        String description) {
    static ExchangeSelection balance() {
        return new ExchangeSelection(ExchangeKind.BALANCE, "balance", 0, 0, 0, "XP残高");
    }
}
