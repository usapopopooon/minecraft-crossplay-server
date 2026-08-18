package net.usapo.eventbridge;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateMarketFormGateway implements BedrockMarketFormGateway {
    private static final int FORM_LISTING_LIMIT = 20;

    private final JavaPlugin plugin;
    private final MarketRepository repository;
    private final FloodgateApi floodgate;

    FloodgateMarketFormGateway(JavaPlugin plugin, MarketRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.floodgate = FloodgateApi.getInstance();
    }

    @Override
    public boolean open(Player player, Consumer<MarketFormAction> actionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("プレイヤーマーケット")
                .content("手持ちアイテムをXPで売買できます。手数料はありません。")
                .button("商品を見る")
                .button("手に持ったスタックを出品")
                .button("自分の出品")
                .button("自分のXP")
                .button("閉じる")
                .validResultHandler(response -> runOnMain(() -> {
                    switch (response.clickedButtonId()) {
                        case 0 -> openListings(player, actionHandler);
                        case 1 -> openSell(player, actionHandler);
                        case 2 -> openMine(player, actionHandler);
                        case 3 -> actionHandler.accept(
                                new MarketFormAction(MarketFormAction.Kind.BALANCE, 0, 0));
                        default -> {
                            // 閉じるボタンでは何もしない。
                        }
                    }
                }))
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openListings(Player player, Consumer<MarketFormAction> actionHandler) {
        List<MarketListing> listings = repository.activeListings().stream()
                .limit(FORM_LISTING_LIMIT)
                .toList();
        if (listings.isEmpty()) {
            sendMessage(player, "現在出品されている商品はありません。");
            return;
        }
        var builder = SimpleForm.builder()
                .title("商品一覧")
                .content("購入する商品を選んでください。新しい出品から最大20件を表示します。");
        listings.forEach(listing -> builder.button("#" + listing.id() + " "
                + listing.label() + "\n" + listing.priceXp() + " XP / "
                + listing.sellerName()));
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < listings.size()) {
                        openBuyConfirmation(player, listings.get(selected), actionHandler);
                    } else {
                        open(player, actionHandler);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openBuyConfirmation(
            Player player,
            MarketListing listing,
            Consumer<MarketFormAction> actionHandler) {
        ModalForm form = ModalForm.builder()
                .title("購入内容の確認")
                .content("#" + listing.id() + " " + listing.label() + "\n価格: "
                        + listing.priceXp() + " XP\n出品者: " + listing.sellerName()
                        + "\n\nこの商品を購入しますか？")
                .button1("購入する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(new MarketFormAction(
                                MarketFormAction.Kind.BUY,
                                listing.id(),
                                listing.priceXp()));
                    } else {
                        openListings(player, actionHandler);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openSell(Player player, Consumer<MarketFormAction> actionHandler) {
        CustomForm form = CustomForm.builder()
                .title("出品する")
                .input("スタック全体の価格（XP）", "例: 3000")
                .validResultHandler(response -> runOnMain(() -> {
                    String input = response.asInput(0).trim();
                    try {
                        int priceXp = Integer.parseInt(input);
                        if (priceXp <= 0) {
                            throw new NumberFormatException("non-positive");
                        }
                        actionHandler.accept(
                                new MarketFormAction(MarketFormAction.Kind.SELL, 0, priceXp));
                    } catch (NumberFormatException error) {
                        sendMessage(player, "価格は1以上の整数で入力してください。");
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openMine(Player player, Consumer<MarketFormAction> actionHandler) {
        List<MarketListing> listings = repository.activeListings().stream()
                .filter(listing -> listing.sellerId().equals(player.getUniqueId()))
                .limit(FORM_LISTING_LIMIT)
                .toList();
        if (listings.isEmpty()) {
            sendMessage(player, "現在の出品はありません。");
            return;
        }
        var builder = SimpleForm.builder()
                .title("自分の出品")
                .content("取り消して返却する商品を選んでください。");
        listings.forEach(listing -> builder.button("#" + listing.id() + " "
                + listing.label() + "\n" + listing.priceXp() + " XP"));
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < listings.size()) {
                        MarketListing listing = listings.get(selected);
                        actionHandler.accept(new MarketFormAction(
                                MarketFormAction.Kind.CANCEL,
                                listing.id(),
                                listing.priceXp()));
                    } else {
                        open(player, actionHandler);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void sendForm(Player player, org.geysermc.cumulus.form.Form form) {
        if (!player.isOnline()) {
            return;
        }
        if (!floodgate.sendForm(player.getUniqueId(), form)) {
            sendMessage(player, "フォームを表示できませんでした。/market コマンドをお試しください。");
        }
    }

    private void sendMessage(Player player, String message) {
        if (player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private void runOnMain(Runnable operation) {
        plugin.getServer().getScheduler().runTask(plugin, operation);
    }
}
