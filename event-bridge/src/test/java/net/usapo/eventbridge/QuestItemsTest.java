package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;

final class QuestItemsTest {
    @Test
    void acceptsAnEnchantedBookAndRequiresItsExactEnchantmentsAtSubmission() {
        Keyed mendingEnchantment = keyed("mending");
        ItemStack mending = enchantedBook(mendingEnchantment, 1);
        ItemStack anotherMendingBook = enchantedBook(mendingEnchantment, 1);
        ItemStack unbreaking = enchantedBook(keyed("unbreaking"), 3);
        QuestListing quest = quest(mending);

        assertTrue(QuestItems.isSupportedRequest(mending));
        assertTrue(QuestItems.isSupportedReward(mending));
        assertTrue(QuestItems.matchesRequested(quest, anotherMendingBook));
        assertFalse(QuestItems.matchesRequested(quest, unbreaking));
    }

    @Test
    void rejectsAnEnchantedBookWithoutAStoredEnchantment() {
        ItemStack empty = enchantedBook(null, 0);

        assertFalse(QuestItems.isSupportedRequest(empty));
        assertFalse(QuestItems.isSupportedReward(empty));
        assertEquals(
                "保存エンチャントのない本は依頼品にできません。種類・レベルが付いたエンチャント本を手に持ってください。",
                QuestItems.requestRejectionMessage(empty));
    }

    @Test
    void requiresTheVisibleCustomBookNameButIgnoresOtherMetadata() {
        Keyed mending = keyed("mending");
        ItemStack requested = enchantedBook(mending, 1, Component.text("秘密の教本"));
        ItemStack sameVisibleName = enchantedBook(mending, 1, Component.text("秘密の教本"));
        ItemStack defaultName = enchantedBook(
                mending, 1, Component.translatable("item.minecraft.enchanted_book"));
        QuestListing quest = quest(requested, "秘密の教本（修繕）");

        assertTrue(QuestItems.matchesRequested(quest, sameVisibleName));
        assertFalse(QuestItems.matchesRequested(quest, defaultName));
    }

    @Test
    void explainsWhichEnchantedBookWasRequiredAndHeld() {
        ItemStack mending = enchantedBook(keyed("mending"), 1);
        ItemStack unbreaking = enchantedBook(keyed("unbreaking"), 3);

        assertEquals(
                "必要な本: エンチャントの本（修繕） / 手持ち: エンチャントの本（耐久力 III）。"
                        + "エンチャントの種類・レベルと本の名前を確認してください。",
                QuestItems.submissionMismatchMessage(quest(mending), unbreaking));
    }

    private static QuestListing quest(ItemStack requestedItem) {
        return quest(requestedItem, "エンチャントの本（修繕）");
    }

    private static QuestListing quest(ItemStack requestedItem, String requestedItemName) {
        return new QuestListing(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Owner",
                "minecraft:enchanted_book",
                requestedItemName,
                requestedItem,
                1,
                24,
                simpleItem("diamond", 3),
                QuestListing.Status.OPEN,
                null,
                null,
                1_000,
                2_000,
                0,
                UUID.randomUUID());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack enchantedBook(Keyed enchantment, int level) {
        return enchantedBook(
                enchantment,
                level,
                Component.translatable("item.minecraft.enchanted_book"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack enchantedBook(
            Keyed enchantment, int level, Component effectiveName) {
        Material material = material("enchanted_book");
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        Map<Keyed, Integer> enchantments =
                enchantment == null ? Map.of() : Map.of(enchantment, level);
        when(meta.getStoredEnchants()).thenReturn((Map) enchantments);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.effectiveName()).thenReturn(effectiveName);
        return item;
    }

    private static ItemStack simpleItem(String key, int amount) {
        ItemStack item = mock(ItemStack.class);
        Material material = material(key);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        return item;
    }

    @SuppressWarnings("deprecation")
    private static Material material(String key) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return material;
    }

    private static Keyed keyed(String key) {
        Keyed enchantment = mock(Keyed.class);
        when(enchantment.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return enchantment;
    }
}
