package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

record QuestListing(
        long id,
        UUID eventId,
        UUID ownerId,
        String ownerName,
        String requestedItemId,
        String requestedItemName,
        int requestedCount,
        int fulfillmentHours,
        ItemStack reward,
        Status status,
        UUID workerId,
        String workerName,
        long createdAtMillis,
        long openExpiresAtMillis,
        long acceptedDeadlineMillis,
        UUID lastTransitionId) {
    enum Status {
        OPEN,
        ACCEPTED,
        COMPLETED,
        CANCELLED
    }

    QuestListing {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(requestedItemId, "requestedItemId");
        Objects.requireNonNull(requestedItemName, "requestedItemName");
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastTransitionId, "lastTransitionId");
        if (id <= 0
                || ownerName.isBlank()
                || !requestedItemId.startsWith("minecraft:")
                || requestedItemName.isBlank()
                || requestedCount <= 0
                || fulfillmentHours < 1
                || fulfillmentHours > 72
                || reward.getType().isAir()
                || reward.getAmount() <= 0
                || createdAtMillis < 0
                || openExpiresAtMillis <= createdAtMillis) {
            throw new IllegalArgumentException("invalid quest listing");
        }
        boolean hasWorker = workerId != null && workerName != null && !workerName.isBlank();
        if ((status == Status.ACCEPTED || status == Status.COMPLETED) != hasWorker) {
            throw new IllegalArgumentException("invalid quest worker state");
        }
        if ((status == Status.ACCEPTED || status == Status.COMPLETED)
                != (acceptedDeadlineMillis > 0)) {
            throw new IllegalArgumentException("invalid quest deadline state");
        }
        reward = reward.clone();
    }

    @Override
    public ItemStack reward() {
        return reward.clone();
    }

    String requestedLabel() {
        return requestedItemName + " x" + requestedCount;
    }

    String rewardLabel() {
        return MarketItems.displayName(reward) + " x" + reward.getAmount();
    }
}
