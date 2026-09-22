package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

final class WorldTravelPromptTest {
    @Test
    void javaPlayerGetsDedicated27SlotMenuWithBothActions() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        BedrockWorldTravelPrompt bedrock = mock(BedrockWorldTravelPrompt.class);
        WorldTravelPrompt prompt = new WorldTravelPrompt(plugin, bedrock);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        Inventory inventory = mock(Inventory.class);
        when(player.openInventory(inventory)).thenReturn(mock(InventoryView.class));
        ItemStack icon = mock(ItemStack.class);

        try (var bukkit = mockStatic(Bukkit.class);
                var icons = mockStatic(JavaChestMenus.class)) {
            bukkit.when(() -> Bukkit.createInventory(
                    any(WorldTravelPrompt.PromptHolder.class), eq(27), any(Component.class)))
                    .thenReturn(inventory);
            icons.when(() -> JavaChestMenus.icon(any(), any(), any())).thenReturn(icon);

            assertTrue(prompt.open(player, "world_2", () -> {}, () -> {}));

            bukkit.verify(() -> Bukkit.createInventory(
                    any(WorldTravelPrompt.PromptHolder.class), eq(27),
                    eq(Component.text("ワールド移動の確認").decoration(TextDecoration.ITALIC, false))));
            verify(inventory).setItem(4, icon);
            verify(inventory).setItem(WorldTravelPrompt.CONFIRM_SLOT, icon);
            verify(inventory).setItem(WorldTravelPrompt.CANCEL_SLOT, icon);
        }
        verify(bedrock, never()).open(any(), any(), any(), any());
        verify(player, never()).getInventory();
    }

    @Test
    void confirmationResolvesBeforeCloseAndRunsOnlyOnceAfterClose() {
        Harness h = harness();
        doAnswer(invocation -> {
            assertEquals(0, h.confirmed.get());
            h.prompt.onInventoryClose(h.close);
            return null;
        }).when(h.player).closeInventory();
        InventoryClickEvent click = h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.LEFT);

        h.prompt.onInventoryClick(click);
        h.prompt.onInventoryClick(click);

        assertEquals(1, h.scheduled.size());
        assertEquals(0, h.confirmed.get());
        verify(h.player, never()).closeInventory();
        h.scheduled.getFirst().run();
        assertEquals(1, h.confirmed.get());
        assertEquals(0, h.cancelled.get());
        assertEquals(1, h.scheduled.size());
        verify(h.player).closeInventory();
        verify(h.player, never()).getInventory();
    }

    @Test
    void cancelButtonAndCloseResolveOnlyOnce() {
        Harness h = harness();
        doAnswer(invocation -> {
            h.prompt.onInventoryClose(h.close);
            return null;
        }).when(h.player).closeInventory();

        h.prompt.onInventoryClick(h.click(WorldTravelPrompt.CANCEL_SLOT, ClickType.RIGHT));
        h.scheduled.getFirst().run();
        h.prompt.onInventoryClose(h.close);

        assertEquals(0, h.confirmed.get());
        assertEquals(1, h.cancelled.get());
        assertEquals(1, h.scheduled.size());
    }

    @Test
    void closingWithoutSelectionCancelsOnceAndCannotLaterConfirm() {
        Harness h = harness();

        h.prompt.onInventoryClose(h.close);
        h.prompt.onInventoryClose(h.close);
        h.prompt.onInventoryClick(h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.LEFT));

        assertEquals(1, h.scheduled.size());
        h.scheduled.getFirst().run();
        assertEquals(1, h.cancelled.get());
        assertEquals(0, h.confirmed.get());
        verify(h.player, never()).closeInventory();
    }

    @Test
    void otherPlayerEvenWithSameUuidCannotResolveOwnerPrompt() {
        Harness h = harness();
        UUID id = UUID.randomUUID();
        when(h.player.getUniqueId()).thenReturn(id);
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(id);
        InventoryClickEvent click = h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.LEFT);
        when(click.getWhoClicked()).thenReturn(other);
        when(h.close.getPlayer()).thenReturn(other);

        h.prompt.onInventoryClick(click);
        h.prompt.onInventoryClose(h.close);

        verify(click).setCancelled(true);
        assertTrue(h.scheduled.isEmpty());
    }

    @Test
    void bottomClicksShiftClicksNumberKeysAndAllDragsAreBlockedWithoutResolving() {
        Harness h = harness();
        for (InventoryClickEvent click : List.of(
                h.click(38, ClickType.LEFT),
                h.click(-999, ClickType.LEFT),
                h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.SHIFT_LEFT),
                h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.NUMBER_KEY))) {
            h.prompt.onInventoryClick(click);
            verify(click).setCancelled(true);
        }
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(h.view);
        h.prompt.onInventoryDrag(drag);

        verify(drag).setCancelled(true);
        assertTrue(h.scheduled.isEmpty());
        verify(h.player, never()).getInventory();
    }

    @Test
    void resolvedPromptDoesNotCloseAnUnrelatedNewMenu() {
        Harness h = harness();
        h.prompt.onInventoryClick(h.click(WorldTravelPrompt.CONFIRM_SLOT, ClickType.LEFT));
        InventoryView other = mock(InventoryView.class);
        when(other.getTopInventory()).thenReturn(mock(Inventory.class));
        when(h.player.getOpenInventory()).thenReturn(other);

        h.scheduled.getFirst().run();

        verify(h.player, never()).closeInventory();
        assertEquals(1, h.confirmed.get());
    }

    @Test
    void bedrockSendFailureDoesNotFallBackToJavaMenu() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        BedrockWorldTravelPrompt bedrock = mock(BedrockWorldTravelPrompt.class);
        WorldTravelPrompt prompt = new WorldTravelPrompt(plugin, bedrock);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(bedrock.isBedrockPlayer(player)).thenReturn(true);
        Runnable confirm = mock(Runnable.class);
        Runnable cancel = mock(Runnable.class);
        when(bedrock.open(player, "world_2", confirm, cancel)).thenReturn(false);

        try (var bukkit = mockStatic(Bukkit.class)) {
            assertFalse(prompt.open(player, "world_2", confirm, cancel));
            bukkit.verifyNoInteractions();
        }

        verify(bedrock).open(player, "world_2", confirm, cancel);
        verify(confirm, never()).run();
        verify(cancel, never()).run();
    }

    private static Harness harness() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        List<Runnable> scheduled = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return null;
        });
        Player player = mock(Player.class);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        WorldTravelPrompt.PromptHolder holder = new WorldTravelPrompt.PromptHolder(
                player, confirmed::incrementAndGet, cancelled::incrementAndGet);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        holder.attach(inventory);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getInventory()).thenReturn(inventory);
        when(close.getPlayer()).thenReturn(player);
        return new Harness(new WorldTravelPrompt(plugin, null), player, view, close,
                scheduled, confirmed, cancelled);
    }

    private record Harness(
            WorldTravelPrompt prompt,
            Player player,
            InventoryView view,
            InventoryCloseEvent close,
            List<Runnable> scheduled,
            AtomicInteger confirmed,
            AtomicInteger cancelled) {
        InventoryClickEvent click(int slot, ClickType type) {
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getView()).thenReturn(view);
            when(click.getWhoClicked()).thenReturn(player);
            when(click.getRawSlot()).thenReturn(slot);
            when(click.getClick()).thenReturn(type);
            return click;
        }
    }
}
