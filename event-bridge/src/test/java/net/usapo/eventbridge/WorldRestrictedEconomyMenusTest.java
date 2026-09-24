package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.impl.FormImpl;
import org.geysermc.cumulus.response.ModalFormResponse;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.response.result.ValidFormResponseResult;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorldRestrictedEconomyMenusTest {
    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void bedrockExchangeRechecksEveryNavigationAndConfirmationCallback(String world) throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            Harness harness = harness();
            List<ExchangeSelection> selections = new ArrayList<>();
            var gateway = new FloodgateExchangeFormGateway(harness.plugin(), harness.floodgate());
            assertTrue(gateway.open(harness.player(), selections::add));
            for (int index = 0; index < stage; index++) {
                click(harness.forms().getLast());
                harness.scheduled().removeFirst().run();
            }
            int before = harness.forms().size();
            click(harness.forms().getLast());
            setWorld(harness.player(), world);
            harness.scheduled().removeFirst().run();

            assertEquals(before, harness.forms().size());
            assertTrue(selections.isEmpty());
            verify(harness.player()).sendMessage(WorldEconomyPolicy.MESSAGE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void bedrockGachaRechecksEveryNavigationAndConfirmationCallback(String world) throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            Harness harness = harness();
            List<ItemGachaSelection> selections = new ArrayList<>();
            var gateway = new FloodgateGachaFormGateway(harness.plugin(), harness.floodgate());
            assertTrue(gateway.open(harness.player(), selections::add));
            for (int index = 0; index < stage; index++) {
                click(harness.forms().getLast());
                harness.scheduled().removeFirst().run();
            }
            int before = harness.forms().size();
            click(harness.forms().getLast());
            setWorld(harness.player(), world);
            harness.scheduled().removeFirst().run();

            assertEquals(before, harness.forms().size());
            assertTrue(selections.isEmpty());
            verify(harness.player()).sendMessage(WorldEconomyPolicy.MESSAGE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void menusRefuseToOpenDirectlyInRestrictedWorld(String world) {
        Harness harness = harness();
        setWorld(harness.player(), world);
        assertTrue(new FloodgateExchangeFormGateway(harness.plugin(), harness.floodgate())
                .open(harness.player(), ignored -> {}));
        assertTrue(new FloodgateGachaFormGateway(harness.plugin(), harness.floodgate())
                .open(harness.player(), ignored -> {}));
        assertTrue(new JavaExchangeChestMenu(harness.plugin()).open(harness.player(), ignored -> {}));

        assertTrue(harness.forms().isEmpty());
        verify(harness.player(), never()).openInventory(any(Inventory.class));
        verify(harness.player(), org.mockito.Mockito.times(3)).sendMessage(WorldEconomyPolicy.MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void queuedJavaMenuButtonChecksWorldAtExecutionAndClosesMenu(String world) throws Exception {
        Harness harness = harness();
        Runnable action = mock(Runnable.class);
        Inventory inventory = mock(Inventory.class);
        var holderConstructor = Class.forName(JavaExchangeChestMenu.class.getName() + "$MenuHolder")
                .getDeclaredConstructor(UUID.class, Map.class);
        holderConstructor.setAccessible(true);
        InventoryHolder holder = (InventoryHolder) holderConstructor.newInstance(
                harness.player().getUniqueId(), Map.of(10, action));
        when(inventory.getHolder(false)).thenReturn(holder);
        when(inventory.getSize()).thenReturn(27);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(harness.player());
        when(event.getRawSlot()).thenReturn(10);

        new JavaExchangeChestMenu(harness.plugin()).onInventoryClick(event);
        setWorld(harness.player(), world);
        harness.scheduled().removeFirst().run();

        verify(event).setCancelled(true);
        verify(action, never()).run();
        verify(harness.player()).sendMessage(WorldEconomyPolicy.MESSAGE);
        verify(harness.player()).closeInventory();
    }

    private static Harness harness() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        setWorld(player, "world");
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
        FloodgateApi floodgate = mock(FloodgateApi.class);
        when(floodgate.isFloodgatePlayer(playerId)).thenReturn(true);
        List<Form> forms = new ArrayList<>();
        when(floodgate.sendForm(eq(playerId), any(Form.class))).thenAnswer(invocation -> {
            forms.add(invocation.getArgument(1));
            return true;
        });
        return new Harness(plugin, floodgate, player, forms, scheduled);
    }

    private static void setWorld(Player player, String name) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(name));
        when(player.getWorld()).thenReturn(world);
    }

    private static void click(Form form) throws Exception {
        if (form instanceof SimpleForm) {
            SimpleFormResponse response = mock(SimpleFormResponse.class);
            when(response.clickedButtonId()).thenReturn(0);
            implementation(form).callResultHandler(ValidFormResponseResult.of(response));
        } else {
            assertInstanceOf(ModalForm.class, form);
            ModalFormResponse response = mock(ModalFormResponse.class);
            when(response.clickedFirst()).thenReturn(true);
            implementation(form).callResultHandler(ValidFormResponseResult.of(response));
        }
    }

    @SuppressWarnings("unchecked")
    private static <R extends org.geysermc.cumulus.response.FormResponse> FormImpl<R> implementation(
            Form form) {
        return (FormImpl<R>) assertInstanceOf(FormImpl.class, form);
    }

    private record Harness(JavaPlugin plugin, FloodgateApi floodgate, Player player,
            List<Form> forms, List<Runnable> scheduled) {}
}
