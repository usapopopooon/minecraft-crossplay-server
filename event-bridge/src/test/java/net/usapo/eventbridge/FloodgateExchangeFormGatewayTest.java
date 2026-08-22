package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
import org.junit.jupiter.api.Test;

final class FloodgateExchangeFormGatewayTest {
    @Test
    void resourceFlowChoosesItemThenAmountAndRequiresConfirmation() throws Exception {
        Harness harness = harness();
        List<ExchangeSelection> selections = new ArrayList<>();

        assertTrue(harness.gateway().open(harness.player(), selections::add));
        SimpleForm root = assertInstanceOf(SimpleForm.class, harness.forms().get(0));
        assertEquals("XPで資源を購入", root.buttons().get(1).text());
        click(root, 1);

        SimpleForm resources = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertEquals("XPで資源を購入", resources.title());
        assertEquals(
                List.of(
                        "エメラルド\nx4 / x16 / x32 / x64",
                        "火薬\nx64",
                        "ダイヤモンド\nx1 / x3 / x8 / x16",
                        "戻る"),
                resources.buttons().stream().map(button -> button.text()).toList());
        click(resources, 1);

        SimpleForm amounts = assertInstanceOf(SimpleForm.class, harness.forms().get(2));
        assertEquals("火薬へ交換", amounts.title());
        assertEquals(2, amounts.buttons().size());
        assertEquals("サーバーXP 150 → 火薬 x64", amounts.buttons().get(0).text());
        assertEquals("戻る", amounts.buttons().get(1).text());
        click(amounts, 0);

        ModalForm firstConfirmation =
                assertInstanceOf(ModalForm.class, harness.forms().get(3));
        assertTrue(firstConfirmation.content().contains("サーバーXP 150 → 火薬 x64"));
        assertTrue(selections.isEmpty());
        choose(firstConfirmation, false);

        SimpleForm returnedAmounts =
                assertInstanceOf(SimpleForm.class, harness.forms().get(4));
        assertEquals("火薬へ交換", returnedAmounts.title());
        click(returnedAmounts, 0);
        ModalForm finalConfirmation =
                assertInstanceOf(ModalForm.class, harness.forms().get(5));
        choose(finalConfirmation, true);

        assertEquals(
                List.of(new ExchangeCatalog().findResource("gunpowder", 64).orElseThrow()),
                selections);
    }

    @Test
    void resourceBackButtonsReturnOneLevelAtATime() throws Exception {
        Harness harness = harness();

        harness.gateway().open(harness.player(), ignored -> {});
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 1);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(1)), 1);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(2)), 1);

        SimpleForm resources = assertInstanceOf(SimpleForm.class, harness.forms().get(3));
        assertEquals("XPで資源を購入", resources.title());
        click(resources, 3);

        SimpleForm root = assertInstanceOf(SimpleForm.class, harness.forms().get(4));
        assertEquals("資源交換所", root.title());
    }

    @Test
    void buybackUsesEmeraldAndMaterialAsTheTwoPlainCategories() throws Exception {
        Harness harness = harness();
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(harness.player().getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

        harness.gateway().open(harness.player(), ignored -> {});
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 3);

        SimpleForm categories = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertEquals("資源をXPで売却", categories.title());
        assertEquals(
                List.of(
                        "エメラルド\n所持0個 / 64個 → 500 XP",
                        "資材\n土・砂・砂岩・深層岩など",
                        "戻る"),
                categories.buttons().stream().map(button -> button.text()).toList());
    }

    @Test
    void openedResourceFormKeepsItsVisibleSelectionWhenCatalogChanges() throws Exception {
        ExchangeCatalog catalog = new ExchangeCatalog(
                1,
                List.of(ExchangeCatalog.resource(
                        "minecraft:copper_ingot", "銅インゴット", 4, 75)));
        Harness harness = harness(catalog);

        harness.gateway().open(harness.player(), ignored -> {});
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 1);
        SimpleForm resources = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertEquals("銅インゴット\nx4", resources.buttons().getFirst().text());

        catalog.replaceResources(
                2,
                List.of(ExchangeCatalog.resource(
                        "minecraft:diamond", "ダイヤモンド", 1, 720)));
        click(resources, 0);

        SimpleForm amounts = assertInstanceOf(SimpleForm.class, harness.forms().get(2));
        assertEquals("銅インゴットへ交換", amounts.title());
    }

    private static Harness harness() {
        return harness(new ExchangeCatalog());
    }

    private static Harness harness(ExchangeCatalog catalog) {
        UUID playerId = UUID.randomUUID();
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        });
        FloodgateApi floodgate = mock(FloodgateApi.class);
        when(floodgate.isFloodgatePlayer(playerId)).thenReturn(true);
        List<Form> forms = new ArrayList<>();
        when(floodgate.sendForm(eq(playerId), any(Form.class))).thenAnswer(invocation -> {
            forms.add(invocation.getArgument(1));
            return true;
        });
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        return new Harness(
                new FloodgateExchangeFormGateway(plugin, floodgate, catalog),
                player,
                forms);
    }

    private static void click(SimpleForm form, int button) throws Exception {
        SimpleFormResponse response = mock(SimpleFormResponse.class);
        when(response.clickedButtonId()).thenReturn(button);
        formImplementation(form).callResultHandler(ValidFormResponseResult.of(response));
    }

    private static void choose(ModalForm form, boolean first) throws Exception {
        ModalFormResponse response = mock(ModalFormResponse.class);
        when(response.clickedFirst()).thenReturn(first);
        formImplementation(form).callResultHandler(ValidFormResponseResult.of(response));
    }

    @SuppressWarnings("unchecked")
    private static <R extends org.geysermc.cumulus.response.FormResponse>
            FormImpl<R> formImplementation(Form form) {
        return (FormImpl<R>) assertInstanceOf(FormImpl.class, form);
    }

    private record Harness(
            FloodgateExchangeFormGateway gateway, Player player, List<Form> forms) {}
}
