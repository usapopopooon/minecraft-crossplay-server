package net.usapo.eventbridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

final class MarketItems {
    private MarketItems() {}

    static String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            String custom = PlainTextComponentSerializer.plainText()
                    .serialize(meta.displayName())
                    .strip();
            if (!custom.isEmpty()) {
                return custom;
            }
        }
        return item.getType().getKey().getKey().replace('_', ' ');
    }

    static boolean canFit(PlayerInventory inventory, ItemStack incoming) {
        int remaining = incoming.getAmount();
        for (ItemStack stored : inventory.getStorageContents()) {
            if (stored == null || stored.getType().isAir()) {
                remaining -= incoming.getMaxStackSize();
            } else if (stored.isSimilar(incoming)) {
                remaining -= Math.max(0, stored.getMaxStackSize() - stored.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    static ItemStack[] snapshot(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            snapshot[index] = contents[index] == null ? null : contents[index].clone();
        }
        return snapshot;
    }
}
