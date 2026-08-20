package net.usapo.eventbridge;

import org.bukkit.inventory.ItemStack;

final class QuestItems {
    private QuestItems() {}

    static boolean isSimpleStack(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0
                && item.getMaxStackSize() > 1
                && !item.hasItemMeta();
    }

    static boolean matchesRequested(QuestListing quest, ItemStack item) {
        return isSimpleStack(item)
                && quest.requestedItemId().equals(item.getType().getKey().toString())
                && item.getAmount() >= quest.requestedCount();
    }

    static ItemStack removeRequested(ItemStack held, int count) {
        if (held == null || count <= 0 || held.getAmount() < count) {
            throw new IllegalArgumentException("insufficient held item");
        }
        ItemStack submitted = held.clone();
        submitted.setAmount(count);
        return submitted;
    }
}
