package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

record MarketClaim(
        UUID id,
        long listingId,
        UUID ownerId,
        ItemStack item,
        Status status,
        UUID transferId) {
    enum Status {
        PENDING,
        DELIVERING,
        DELIVERED
    }

    MarketClaim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        if (listingId <= 0 || item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("invalid market claim");
        }
        if ((status == Status.DELIVERING || status == Status.DELIVERED) != (transferId != null)) {
            throw new IllegalArgumentException("invalid market claim transfer state");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
