package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

record MarketListing(
        long id,
        UUID eventId,
        UUID sellerId,
        String sellerName,
        int priceXp,
        ItemStack item,
        Status status,
        UUID transferId,
        UUID recipientId) {
    enum Status {
        ACTIVE,
        DELIVERING,
        SOLD,
        CANCELLED
    }

    MarketListing {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(sellerName, "sellerName");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        if (id <= 0
                || sellerName.isBlank()
                || priceXp <= 0
                || item.getType().isAir()
                || item.getAmount() <= 0) {
            throw new IllegalArgumentException("invalid market listing");
        }
        boolean hasTransfer = transferId != null && recipientId != null;
        if ((status == Status.ACTIVE && (transferId != null || recipientId != null))
                || (status != Status.ACTIVE && !hasTransfer)) {
            throw new IllegalArgumentException("invalid market listing transfer state");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    String label() {
        return MarketItems.displayName(item) + " x" + item.getAmount();
    }
}
