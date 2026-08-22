package net.usapo.eventbridge;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

final class QuestItems {
    private QuestItems() {}

    static boolean isSimpleStack(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0
                && item.getMaxStackSize() > 1
                && !item.hasItemMeta();
    }

    static boolean isSupportedRequest(ItemStack item) {
        return isSimpleStack(item) || isEnchantedBook(item);
    }

    static boolean isSupportedReward(ItemStack item) {
        return isSimpleStack(item) || isEnchantedBook(item);
    }

    static boolean isSupportedAdminItem(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getType().isItem()
                && item.getAmount() > 0
                && !item.hasItemMeta();
    }

    static boolean matchesRequested(QuestListing quest, ItemStack item) {
        if (QuestIssuer.isSystem(quest)) {
            ItemStack requestedItem = quest.requestedItem();
            return requestedItem != null
                    && isSupportedAdminItem(item)
                    && quest.requestedItemId().equals(item.getType().getKey().toString())
                    && item.getAmount() >= quest.requestedCount()
                    && requestedItem.isSimilar(item);
        }
        if (!isSupportedRequest(item)
                || !quest.requestedItemId().equals(item.getType().getKey().toString())
                || item.getAmount() < quest.requestedCount()) {
            return false;
        }
        ItemStack requestedItem = quest.requestedItem();
        if (requestedItem == null) {
            return isSimpleStack(item);
        }
        if (isEnchantedBook(requestedItem)) {
            EnchantmentStorageMeta requestedMeta =
                    (EnchantmentStorageMeta) requestedItem.getItemMeta();
            EnchantmentStorageMeta submittedMeta =
                    (EnchantmentStorageMeta) item.getItemMeta();
            return requestedMeta.getStoredEnchants().equals(submittedMeta.getStoredEnchants())
                    && MarketItems.displayName(requestedItem)
                            .equals(MarketItems.displayName(item));
        }
        return requestedItem.isSimilar(item);
    }

    static boolean hasFixedRequestCount(ItemStack item) {
        return isEnchantedBook(item);
    }

    static String requestRejectionMessage(ItemStack item) {
        if (isEnchantedBookType(item)) {
            return "保存エンチャントのない本は依頼品にできません。種類・レベルが付いたエンチャント本を手に持ってください。";
        }
        return "依頼品は、通常のスタック可能アイテムか、エンチャント本を手に持ってください。";
    }

    static String rewardRejectionMessage(ItemStack item) {
        if (isEnchantedBookType(item)) {
            return "保存エンチャントのない本は報酬にできません。種類・レベルが付いたエンチャント本を手に持ってください。";
        }
        return "報酬にする通常のスタック可能アイテムか、エンチャント本を手に持ってください。";
    }

    static String submissionMismatchMessage(QuestListing quest, ItemStack held) {
        ItemStack requestedItem = quest.requestedItem();
        if (requestedItem != null && isEnchantedBook(requestedItem)) {
            String heldName = held == null || held.getType().isAir()
                    ? "なし"
                    : MarketItems.questDisplayName(held);
            return "必要な本: " + quest.requestedItemName() + " / 手持ち: " + heldName
                    + "。エンチャントの種類・レベルと本の名前を確認してください。";
        }
        return "メインハンドに " + quest.requestedLabel() + " 以上をまとめて持ってください。";
    }

    static ItemStack removeRequested(ItemStack held, int count) {
        if (held == null || count <= 0 || held.getAmount() < count) {
            throw new IllegalArgumentException("insufficient held item");
        }
        ItemStack submitted = held.clone();
        submitted.setAmount(count);
        return submitted;
    }

    static boolean isEnchantedBook(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0
                && item.getType().getKey().toString().equals("minecraft:enchanted_book")
                && item.getItemMeta() instanceof EnchantmentStorageMeta meta
                && !meta.getStoredEnchants().isEmpty();
    }

    private static boolean isEnchantedBookType(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getType().getKey().toString().equals("minecraft:enchanted_book");
    }
}
