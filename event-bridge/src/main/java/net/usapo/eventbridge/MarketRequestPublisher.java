package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class MarketRequestPublisher implements MarketRequestSink {
    static final String LISTING_PREFIX = "USAPO_MARKET_LISTING|1|";
    static final String REQUEST_PREFIX = "USAPO_MARKET_REQUEST|1|";

    private final Consumer<String> logSink;
    private final Clock clock;
    private final Supplier<UUID> requestIds;

    MarketRequestPublisher(Consumer<String> logSink) {
        this(logSink, Clock.systemUTC(), UUID::randomUUID);
    }

    MarketRequestPublisher(
            Consumer<String> logSink, Clock clock, Supplier<UUID> requestIds) {
        this.logSink = logSink;
        this.clock = clock;
        this.requestIds = requestIds;
    }

    @Override
    public void publishListing(MarketListing listing) {
        logSink.accept(LISTING_PREFIX
                + listing.eventId()
                + "|"
                + listing.id()
                + "|"
                + listing.sellerId()
                + "|"
                + encode(listing.sellerName())
                + "|"
                + encode(listing.item().getType().getKey().toString())
                + "|"
                + encode(MarketItems.marketDisplayName(listing.item()))
                + "|"
                + listing.item().getAmount()
                + "|"
                + listing.priceXp()
                + "|"
                + clock.instant().toEpochMilli());
    }

    @Override
    public void publishRequest(
            String kind, long listingId, int expectedPriceXp, Player player) {
        if (!kind.equals("buy") && !kind.equals("cancel") && !kind.equals("balance")) {
            throw new IllegalArgumentException("invalid market request kind");
        }
        logSink.accept(REQUEST_PREFIX
                + requestIds.get()
                + "|"
                + kind
                + "|"
                + listingId
                + "|"
                + player.getUniqueId()
                + "|"
                + encode(player.getName())
                + "|"
                + expectedPriceXp
                + "|"
                + clock.instant().toEpochMilli());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
