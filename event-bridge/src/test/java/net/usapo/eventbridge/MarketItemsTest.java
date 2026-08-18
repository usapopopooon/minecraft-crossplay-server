package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
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

    private static ItemStack item(String materialKey, Component effectiveName) {
        Material material = mock(Material.class);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(materialKey));
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.effectiveName()).thenReturn(effectiveName);
        return item;
    }
}
