package net.usapo.eventbridge;

import org.bukkit.entity.Player;

interface MarketRequestSink {
    void publishListing(MarketListing listing);

    void publishRequest(String kind, long listingId, int expectedPriceXp, Player player);
}
