package net.usapo.eventbridge;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

record QuestClaim(
        UUID id,
        long questId,
        UUID ownerId,
        ItemStack item,
        Status status,
        UUID transferId) {
    enum Status {
        PENDING,
        DELIVERING,
        DELIVERED
    }

    QuestClaim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        if (questId <= 0 || item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("invalid quest claim");
        }
        if ((status == Status.DELIVERING || status == Status.DELIVERED) != (transferId != null)) {
            throw new IllegalArgumentException("invalid quest claim transfer state");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
