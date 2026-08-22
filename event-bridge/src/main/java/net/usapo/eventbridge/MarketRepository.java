package net.usapo.eventbridge;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

interface MarketRepository {
    MarketListing create(
            UUID eventId,
            UUID sellerId,
            String sellerName,
            int priceXp,
            ItemStack item)
            throws IOException;

    Optional<MarketListing> find(long listingId);

    Optional<MarketListing> findByEventId(UUID eventId);

    List<MarketListing> activeListings();

    MarketMailboxReturn returnToMailbox(long listingId, UUID requestId, UUID sellerId)
            throws IOException;

    List<MarketClaim> pendingClaims(UUID ownerId);

    MarketClaim prepareClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;

    MarketClaim completeClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;

    void abortClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;

    MarketListing prepareTransfer(long listingId, UUID transferId, UUID recipientId)
            throws IOException;

    MarketListing completeTransfer(
            long listingId,
            UUID transferId,
            MarketListing.Status completedStatus)
            throws IOException;

    void abortTransfer(long listingId, UUID transferId) throws IOException;
}
