package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class JavaQuestChestMenuTest {
    @Test
    void enchantedBookSkipsTheRequestCountMenu() throws Exception {
        QuestRepository repository = mock(QuestRepository.class);
        JavaChestMenus menus = mock(JavaChestMenus.class);
        JavaQuestChestMenu gateway = new JavaQuestChestMenu(
                repository, menus, NamespacedKey.minecraft("quest_draft_test"));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack requested = enchantedBook();
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(requested);
        Method openCreate = JavaQuestChestMenu.class.getDeclaredMethod(
                "openCreate", Player.class, Consumer.class);
        openCreate.setAccessible(true);

        openCreate.invoke(gateway, player, (Consumer<QuestFormAction>) ignored -> {});

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(menus).openNumberInput(
                eq(player),
                title.capture(),
                anyString(),
                anyInt(),
                anyInt(),
                any(ItemStack.class),
                anyList(),
                anyBoolean(),
                any(),
                any());
        assertEquals("納品期限", title.getValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack enchantedBook() {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft("enchanted_book"));
        Keyed mending = mock(Keyed.class);
        when(mending.getKey()).thenReturn(NamespacedKey.minecraft("mending"));
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        when(meta.getStoredEnchants()).thenReturn((Map) Map.of(mending, 1));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.getMaxStackSize()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.effectiveName())
                .thenReturn(Component.translatable("item.minecraft.enchanted_book"));
        return item;
    }
}
