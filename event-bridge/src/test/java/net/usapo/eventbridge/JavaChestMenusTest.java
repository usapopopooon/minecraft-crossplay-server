package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

final class JavaChestMenusTest {
    @Test
    void numberPadBuildsArbitraryPositiveIntegersWithoutTextInput() {
        String digits = "";
        digits = JavaChestMenus.appendDigit(digits, 3, Integer.MAX_VALUE);
        digits = JavaChestMenus.appendDigit(digits, 0, Integer.MAX_VALUE);
        digits = JavaChestMenus.appendDigit(digits, 0, Integer.MAX_VALUE);
        digits = JavaChestMenus.appendDigit(digits, 0, Integer.MAX_VALUE);

        assertEquals("3000", digits);
        assertEquals(3_000, JavaChestMenus.parseDigits(digits));
        assertEquals("300", JavaChestMenus.removeLastDigit(digits));
    }

    @Test
    void numberPadNormalizesLeadingZeroAndRejectsOverflow() {
        assertEquals("5", JavaChestMenus.appendDigit("0", 5, 72));
        assertEquals("72", JavaChestMenus.appendDigit("7", 2, 72));
        assertEquals("7", JavaChestMenus.appendDigit("7", 3, 72));
        assertEquals(0, JavaChestMenus.parseDigits(""));
        assertEquals(0, JavaChestMenus.parseDigits("not-a-number"));
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaChestMenus.appendDigit("", 10, 100));
    }

    @Test
    void numberPadUpdateRunsInPlaceWithoutClosingTheInventory() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        JavaChestMenus menus = new JavaChestMenus(plugin);
        ItemStack icon = mock(ItemStack.class);
        when(icon.clone()).thenReturn(icon);
        AtomicInteger updates = new AtomicInteger();
        JavaChestMenus.MenuEntry entry = JavaChestMenus.updateAction(
                icon, ignored -> updates.incrementAndGet());
        UUID playerId = UUID.randomUUID();
        JavaChestMenus.MenuHolder holder =
                new JavaChestMenus.MenuHolder(playerId, Map.of(4, entry));
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(holder);
        when(top.getSize()).thenReturn(9);
        holder.attach(top);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(4);

        menus.onInventoryClick(event);

        assertEquals(1, updates.get());
        verify(event).setCancelled(true);
        verify(player, never()).closeInventory();
    }
}
