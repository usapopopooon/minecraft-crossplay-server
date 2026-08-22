package net.usapo.eventbridge;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

interface QuestRepository {
    QuestListing create(
            UUID eventId,
            UUID ownerId,
            String ownerName,
            String requestedItemId,
            String requestedItemName,
            int requestedCount,
            int fulfillmentHours,
            ItemStack reward,
            long nowMillis)
            throws IOException;

    QuestListing create(
            UUID eventId,
            UUID ownerId,
            String ownerName,
            String requestedItemId,
            String requestedItemName,
            ItemStack requestedItem,
            int requestedCount,
            int fulfillmentHours,
            ItemStack reward,
            long nowMillis)
            throws IOException;

    Optional<QuestListing> find(long questId);

    Optional<QuestListing> findByEventId(UUID eventId);

    List<QuestListing> openQuests();

    List<QuestListing> nonterminalQuests();

    List<QuestListing> activeFor(UUID playerId);

    List<QuestStatePublication> pendingStatePublications();

    void markStatePublished(long questId, UUID transitionId) throws IOException;

    List<QuestListing> pendingCompletionBroadcasts();

    void markCompletionBroadcasted(long questId, UUID transitionId) throws IOException;

    boolean isProcessed(long questId, UUID transitionId, String kind);

    QuestTransition accept(
            long questId, UUID transitionId, UUID workerId, String workerName, long nowMillis)
            throws IOException;

    QuestTransition abandon(long questId, UUID transitionId, UUID workerId, long nowMillis)
            throws IOException;

    QuestTransition cancel(long questId, UUID transitionId, UUID ownerId) throws IOException;

    QuestTransition invalidate(long questId, UUID transitionId, UUID ownerId) throws IOException;

    QuestTransition complete(
            long questId,
            UUID transitionId,
            UUID workerId,
            ItemStack submittedItem,
            long nowMillis)
            throws IOException;

    List<QuestTransition> expire(long nowMillis) throws IOException;

    List<QuestClaim> pendingClaims(UUID ownerId);

    List<QuestNotice> pendingNotices(UUID playerId);

    void acknowledgeNotice(UUID noticeId, UUID playerId) throws IOException;

    QuestClaim prepareClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;

    QuestClaim completeClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;

    void abortClaim(UUID claimId, UUID ownerId, UUID transferId) throws IOException;
}
