package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

final class JavaChestMenusTest {
    @Test
    void restrictedWorldBlocksImmediateAndPreviouslyQueuedButtons() {
        for (String key : List.of("resource", "world_2_nether", "world_2_the_end")) {
            for (boolean deferred : List.of(false, true)) {
                JavaPlugin plugin = mock(JavaPlugin.class);
                Server server = mock(Server.class);
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                when(plugin.getServer()).thenReturn(server);
                when(server.getScheduler()).thenReturn(scheduler);
                ArrayDeque<Runnable> queued = new ArrayDeque<>();
                doAnswer(invocation -> {
                    queued.add(invocation.getArgument(1));
                    return null;
                }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
                JavaChestMenus menus = new JavaChestMenus(plugin);
                ItemStack icon = mock(ItemStack.class);
                when(icon.clone()).thenReturn(icon);
                when(icon.getItemMeta()).thenReturn(mock(ItemMeta.class));
                AtomicInteger updates = new AtomicInteger();
                JavaChestMenus.MenuEntry entry = deferred
                        ? JavaChestMenus.action(icon, List.of(), updates::incrementAndGet)
                        : JavaChestMenus.updateAction(icon, ignored -> updates.incrementAndGet());
                UUID id = UUID.randomUUID();
                JavaChestMenus.MenuHolder holder = new JavaChestMenus.MenuHolder(id, Map.of(4, entry));
                Inventory top = mock(Inventory.class);
                when(top.getHolder()).thenReturn(holder);
                when(top.getSize()).thenReturn(9);
                holder.attach(top);
                Player player = mock(Player.class);
                when(player.getUniqueId()).thenReturn(id);
                when(player.isOnline()).thenReturn(true);
                World world = mock(World.class);
                when(player.getWorld()).thenReturn(world);
                when(world.getKey()).thenReturn(NamespacedKey.minecraft(deferred ? "overworld" : key));
                InventoryView view = mock(InventoryView.class);
                when(view.getTopInventory()).thenReturn(top);
                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getView()).thenReturn(view);
                when(event.getWhoClicked()).thenReturn(player);
                when(event.getRawSlot()).thenReturn(4);

                menus.onInventoryClick(event);
                when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
                while (!queued.isEmpty()) queued.remove().run();

                assertEquals(0, updates.get(), key);
                verify(event).setCancelled(true);
                verify(player).sendMessage(WorldEconomyPolicy.MESSAGE);
            }
        }
    }

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
