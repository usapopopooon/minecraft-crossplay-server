package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

final class JavaMarketChestMenuTest {
    @Test
    void customNameComponentKeepsItsFormattingInTheGuiPreview() {
        ItemStack original = mock(ItemStack.class);
        ItemStack preview = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(original.clone()).thenReturn(preview);
        when(preview.getItemMeta()).thenReturn(meta);
        when(meta.hasDisplayName()).thenReturn(true);

        assertEquals(preview, JavaMarketChestMenu.marketIcon(original));

        verify(meta, never()).displayName(any(Component.class));
        verify(preview, never()).setItemMeta(meta);
    }
}
