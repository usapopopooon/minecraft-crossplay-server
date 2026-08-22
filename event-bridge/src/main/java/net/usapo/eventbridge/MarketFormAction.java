package net.usapo.eventbridge;

record MarketFormAction(Kind kind, long listingId, int priceXp) {
    enum Kind {
        SELL,
        BUY,
        CANCEL,
        CLAIM,
        BALANCE,
        LIST,
        MINE
    }
}
