package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;

final class MarketItemsTest {
    @Test
    void translatesVanillaEffectiveNamesAndPreservesCustomNames() {
        assertEquals(
                "古代の残骸",
                MarketItems.displayName(item(
                        "ancient_debris", Component.translatable("block.minecraft.ancient_debris"))));
        assertEquals(
                "深夜の残骸",
                MarketItems.displayName(item("ancient_debris", Component.text("深夜の残骸"))));
    }

    @Test
    void marketNameAddsMaterialOnlyToTheGeneratedEnchantmentDescription() {
        Map<Keyed, Integer> enchantments = axeEnchantments();
        assertEquals(
                "効率Ⅴ耐久力Ⅲ修繕付きの斧（ダイヤモンドの斧）",
                MarketItems.marketDisplayName(item(
                        "diamond_axe",
                        Component.text("効率Ⅴ耐久力Ⅲ修繕付きの斧"),
                        enchantments)));
        assertEquals(
                "夜伐り（効率強化 V / 修繕 / 耐久力 III）",
                MarketItems.marketDisplayName(
                        item("diamond_axe", Component.text("夜伐り"), enchantments)));
        assertEquals(
                "古代の残骸",
                MarketItems.marketDisplayName(item(
                        "ancient_debris", Component.translatable("block.minecraft.ancient_debris"))));
    }

    @Test
    void marketNameShowsEveryEnchantmentOnOrdinaryEquipment() {
        Map<Keyed, Integer> enchantments = Map.of(
                keyed("unbreaking"), 3,
                keyed("loyalty"), 3,
                keyed("mending"), 1,
                keyed("impaling"), 5,
                keyed("channeling"), 1);

        assertEquals(
                "トライデント（召雷 / 水生特効 V / 忠誠 III / 修繕 / 耐久力 III）",
                MarketItems.marketDisplayName(item(
                        "trident",
                        Component.translatable("item.minecraft.trident"),
                        enchantments)));
        assertEquals(
                "海神の槍（召雷 / 水生特効 V / 忠誠 III / 修繕 / 耐久力 III）",
                MarketItems.marketDisplayName(
                        item("trident", Component.text("海神の槍"), enchantments)));
    }

    @Test
    void rendersTranslationArgumentsInMinecraftOrder() {
        assertEquals(
                "Yukiの頭",
                MarketItems.displayName(item(
                        "player_head",
                        Component.translatable(
                                "block.minecraft.player_head.named", Component.text("Yuki")))));
        assertEquals(
                "発射物：矢 x 3",
                MarketItems.displayName(item(
                        "crossbow",
                        Component.translatable(
                                "item.minecraft.crossbow.projectile.multiple",
                                Component.text("3"),
                                Component.text("矢")))));
    }

    @Test
    void marketNameShowsStoredEnchantmentsOnEnchantedBooks() {
        Keyed mending = keyed("mending");
        Keyed unbreaking = keyed("unbreaking");

        assertEquals(
                "エンチャントの本（修繕 / 耐久力 III）",
                MarketItems.marketDisplayName(
                        enchantedBook(Component.translatable("item.minecraft.enchanted_book"),
                                Map.of(unbreaking, 3, mending, 1))));
        assertEquals(
                "秘密の教本（修繕）",
                MarketItems.marketDisplayName(
                        enchantedBook(Component.text("秘密の教本"), Map.of(mending, 1))));
        assertEquals(
                "エンチャントの本（水中採掘 / 虫特効 V / 修繕 / ダメージ増加 V / ほか1種類）",
                MarketItems.marketDisplayName(enchantedBook(
                        Component.translatable("item.minecraft.enchanted_book"),
                        Map.of(
                                keyed("unbreaking"), 3,
                                keyed("sharpness"), 5,
                                mending, 1,
                                keyed("bane_of_arthropods"), 5,
                keyed("aqua_affinity"), 1))));
    }

    @Test
    void questNameShowsEveryStoredEnchantment() {
        assertEquals(
                "エンチャントの本（水中採掘 / 虫特効 V / 修繕 / ダメージ増加 V / 耐久力 III）",
                MarketItems.questDisplayName(enchantedBook(
                        Component.translatable("item.minecraft.enchanted_book"),
                        Map.of(
                                keyed("unbreaking"), 3,
                                keyed("sharpness"), 5,
                                keyed("mending"), 1,
                                keyed("bane_of_arthropods"), 5,
                                keyed("aqua_affinity"), 1))));
    }

    private static ItemStack item(String materialKey, Component effectiveName) {
        return item(materialKey, effectiveName, Map.of());
    }

    private static ItemStack item(
            String materialKey,
            Component effectiveName,
            Map<Keyed, Integer> enchantments) {
        Material material = mock(Material.class);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(materialKey));
        when(material.translationKey()).thenReturn(switch (materialKey) {
            case "ancient_debris", "player_head" -> "block.minecraft." + materialKey;
            default -> "item.minecraft." + materialKey;
        });
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.effectiveName()).thenReturn(effectiveName);
        stubEnchantments(item, enchantments);
        return item;
    }

    private static Map<Keyed, Integer> axeEnchantments() {
        return Map.of(
                keyed("efficiency"),
                5,
                keyed("unbreaking"),
                3,
                keyed("mending"),
                1);
    }

    private static ItemStack enchantedBook(
            Component effectiveName, Map<Keyed, Integer> storedEnchantments) {
        ItemStack item = item("enchanted_book", effectiveName);
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        stubStoredEnchantments(meta, storedEnchantments);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubStoredEnchantments(
            EnchantmentStorageMeta meta, Map<Keyed, Integer> enchantments) {
        when(meta.getStoredEnchants()).thenReturn((Map) enchantments);
    }

    private static Keyed keyed(String key) {
        return () -> NamespacedKey.minecraft(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubEnchantments(ItemStack item, Map<Keyed, Integer> enchantments) {
        when(item.getEnchantments()).thenReturn((Map) enchantments);
    }
}
