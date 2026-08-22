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
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
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

final class FloodgateMarketFormGatewayTest {
    @Test
    void formCopyIdentifiesServerXp() {
        assertEquals(
                "手持ちアイテムをサーバーXPで売買できます。",
                FloodgateMarketFormGateway.INTRODUCTION);
        assertEquals("サーバーXP残高", FloodgateMarketFormGateway.BALANCE_BUTTON_LABEL);
        assertEquals(
                "スタック全体の価格（サーバーXP）",
                FloodgateMarketFormGateway.PRICE_INPUT_LABEL);
        assertEquals("3,000 サーバーXP", FloodgateMarketFormGateway.priceLabel(3_000));
        assertEquals(
                List.of(100, 500, 1_000, 3_000, 5_000, 10_000),
                FloodgateMarketFormGateway.quickPrices());
    }

    @Test
    void listingFormPagesPastTwentyItemsWithoutLosingNavigation() throws Exception {
        MarketRepository repository = mock(MarketRepository.class);
        UUID playerId = UUID.randomUUID();
        List<MarketListing> listings = new ArrayList<>();
        for (int id = 1; id <= 21; id++) {
            listings.add(listing(id, UUID.randomUUID()));
        }
        when(repository.activeListings()).thenReturn(listings);
        Harness harness = harness(repository, playerId, null);

        harness.gateway().open(harness.player(), ignored -> {});
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 0);

        SimpleForm first = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertEquals(10, first.buttons().size());
        assertTrue(first.buttons().get(0).text().startsWith("#1 "));
        assertEquals("次のページ", first.buttons().get(8).text());
        click(first, 8);

        SimpleForm second = assertInstanceOf(SimpleForm.class, harness.forms().get(2));
        assertEquals("前のページ", second.buttons().get(8).text());
        assertEquals("次のページ", second.buttons().get(9).text());
        click(second, 9);

        SimpleForm last = assertInstanceOf(SimpleForm.class, harness.forms().get(3));
        assertEquals(7, last.buttons().size());
        assertTrue(last.buttons().get(0).text().startsWith("#17 "));
        assertTrue(last.buttons().get(4).text().startsWith("#21 "));
        assertEquals("前のページ", last.buttons().get(5).text());
        assertEquals("戻る", last.buttons().get(6).text());
    }

    @Test
    void ownListingRequiresConfirmationBeforeCancellation() throws Exception {
        UUID playerId = UUID.randomUUID();
        MarketRepository repository = mock(MarketRepository.class);
        MarketListing own = listing(7, playerId);
        when(repository.activeListings()).thenReturn(List.of(own));
        when(repository.find(7)).thenReturn(java.util.Optional.of(own));
        Harness harness = harness(repository, playerId, null);
        List<MarketFormAction> actions = new ArrayList<>();

        harness.gateway().open(harness.player(), actions::add);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 2);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(1)), 0);

        ModalForm confirmation = assertInstanceOf(ModalForm.class, harness.forms().get(2));
        assertEquals("出品取り消しの確認", confirmation.title());
        assertEquals("出品を取り消す", confirmation.button1());
        assertTrue(actions.isEmpty());

        choose(confirmation, true);

        assertEquals(
                List.of(new MarketFormAction(MarketFormAction.Kind.CANCEL, 7, 3_000)),
                actions);
    }

    @Test
    void quickPriceShowsTheHeldStackAndRequiresFinalConfirmation() throws Exception {
        UUID playerId = UUID.randomUUID();
        MarketRepository repository = mock(MarketRepository.class);
        ItemStack held = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(held.getType()).thenReturn(material);
        when(held.getAmount()).thenReturn(64);
        when(held.clone()).thenReturn(held);
        when(held.effectiveName()).thenReturn(Component.text("ダイヤモンド"));
        when(held.getEnchantments()).thenReturn(Map.of());
        Harness harness = harness(repository, playerId, held);
        List<MarketFormAction> actions = new ArrayList<>();

        harness.gateway().open(harness.player(), actions::add);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 1);

        SimpleForm prices = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertTrue(prices.content().contains("ダイヤモンド x64"));
        assertEquals("3,000 サーバーXP", prices.buttons().get(3).text());
        click(prices, 3);

        ModalForm confirmation = assertInstanceOf(ModalForm.class, harness.forms().get(2));
        assertTrue(confirmation.content().contains("合計価格: 3,000 サーバーXP"));
        assertTrue(actions.isEmpty());
        choose(confirmation, true);

        assertEquals(
                List.of(new MarketFormAction(MarketFormAction.Kind.SELL, 0, 3_000)),
                actions);
    }

    private static MarketListing listing(long id, UUID sellerId) {
        MarketListing listing = mock(MarketListing.class);
        when(listing.id()).thenReturn(id);
        when(listing.sellerId()).thenReturn(sellerId);
        when(listing.sellerName()).thenReturn("Seller" + id);
        when(listing.priceXp()).thenReturn(3_000);
        when(listing.label()).thenReturn("ダイヤモンド x1");
        when(listing.status()).thenReturn(MarketListing.Status.ACTIVE);
        return listing;
    }

    private static Harness harness(
            MarketRepository repository, UUID playerId, ItemStack held) {
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
        if (held != null) {
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(held);
        }
        return new Harness(
                new FloodgateMarketFormGateway(plugin, repository, floodgate),
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
            FloodgateMarketFormGateway gateway, Player player, List<Form> forms) {}
}
