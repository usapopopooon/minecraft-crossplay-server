package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateMarketFormGateway implements BedrockMarketFormGateway {
    private static final int PAGE_SIZE = 8;
    private static final List<Integer> QUICK_PRICES =
            List.of(100, 500, 1_000, 3_000, 5_000, 10_000);
    static final String INTRODUCTION = "手持ちアイテムをサーバーXPで売買できます。";
    static final String BALANCE_BUTTON_LABEL = "サーバーXP残高";
    static final String PRICE_INPUT_LABEL = "スタック全体の価格（サーバーXP）";

    private final JavaPlugin plugin;
    private final MarketRepository repository;
    private final FloodgateApi floodgate;

    FloodgateMarketFormGateway(JavaPlugin plugin, MarketRepository repository) {
        this(plugin, repository, FloodgateApi.getInstance());
    }

    FloodgateMarketFormGateway(
            JavaPlugin plugin, MarketRepository repository, FloodgateApi floodgate) {
        this.plugin = plugin;
        this.repository = repository;
        this.floodgate = floodgate;
    }

    @Override
    public boolean open(Player player, Consumer<MarketFormAction> actionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("プレイヤーマーケット")
                .content(INTRODUCTION)
                .button("商品を見る")
                .button("手に持ったスタックを出品")
                .button("自分の出品")
                .button(BALANCE_BUTTON_LABEL)
                .button("閉じる")
                .validResultHandler(response -> runOnMain(() -> {
                    switch (response.clickedButtonId()) {
                        case 0 -> openListings(player, actionHandler, 1);
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

    private void openListings(
            Player player, Consumer<MarketFormAction> actionHandler, int requestedPage) {
        List<MarketListing> allListings = repository.activeListings();
        if (allListings.isEmpty()) {
            openInfo(
                    player,
                    "商品一覧",
                    "現在出品されている商品はありません。",
                    () -> open(player, actionHandler));
            return;
        }
        BedrockFormPages.Page<MarketListing> page =
                BedrockFormPages.select(allListings, requestedPage, PAGE_SIZE);
        var builder = SimpleForm.builder()
                .title("商品一覧")
                .content("購入する商品を選んでください。 " + page.number() + " / " + page.total());
        List<Runnable> handlers = new ArrayList<>();
        page.items().forEach(listing -> {
            boolean ownListing = listing.sellerId().equals(player.getUniqueId());
            builder.button((ownListing ? "【自分の出品】 " : "") + "#" + listing.id() + " "
                    + listing.label() + "\n" + priceLabel(listing.priceXp()) + " / "
                    + listing.sellerName());
            handlers.add(ownListing
                    ? () -> openOwnListingFromBrowse(
                            player, listing.id(), actionHandler, page.number())
                    : () -> openBuyConfirmation(
                            player, listing.id(), actionHandler, page.number()));
        });
        if (page.number() > 1) {
            builder.button("前のページ");
            handlers.add(() -> openListings(player, actionHandler, page.number() - 1));
        }
        if (page.number() < page.total()) {
            builder.button("次のページ");
            handlers.add(() -> openListings(player, actionHandler, page.number() + 1));
        }
        builder.button("戻る");
        handlers.add(() -> open(player, actionHandler));
        SimpleForm form = builder
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < handlers.size()) {
                        handlers.get(selected).run();
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openBuyConfirmation(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null || listing.status() != MarketListing.Status.ACTIVE) {
            openInfo(
                    player,
                    "商品一覧",
                    "その商品は見つからないか、すでに売り切れています。",
                    () -> openListings(player, actionHandler, page));
            return;
        }
        if (listing.sellerId().equals(player.getUniqueId())) {
            openOwnListingFromBrowse(player, listing.id(), actionHandler, page);
            return;
        }
        ModalForm form = ModalForm.builder()
                .title("購入内容の確認")
                .content("#" + listing.id() + " " + listing.label() + "\n価格: "
                        + priceLabel(listing.priceXp()) + "\n出品者: " + listing.sellerName()
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
                        openListings(player, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openSell(Player player, Consumer<MarketFormAction> actionHandler) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            openInfo(
                    player,
                    "出品する",
                    "出品するアイテムをメインハンドに持ってください。",
                    () -> open(player, actionHandler));
            return;
        }
        openSellPriceChoices(player, held.clone(), actionHandler);
    }

    private void openSellPriceChoices(
            Player player,
            ItemStack shownItem,
            Consumer<MarketFormAction> actionHandler) {
        if (!sameMainHand(player, shownItem)) {
            openHeldItemChanged(player, actionHandler);
            return;
        }
        var builder = SimpleForm.builder()
                .title("出品価格を選ぶ")
                .content("商品: " + itemLabel(shownItem)
                        + "\nスタック全体の合計価格を選んでください。");
        List<Runnable> handlers = new ArrayList<>();
        QUICK_PRICES.forEach(price -> {
            builder.button(priceLabel(price));
            handlers.add(() -> openSellConfirmation(
                    player, shownItem, price, actionHandler));
        });
        builder.button("その他の価格を入力");
        handlers.add(() -> openCustomSellPrice(player, shownItem, actionHandler, null));
        builder.button("戻る");
        handlers.add(() -> open(player, actionHandler));
        SimpleForm form = builder
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < handlers.size()) {
                        handlers.get(selected).run();
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openCustomSellPrice(
            Player player,
            ItemStack shownItem,
            Consumer<MarketFormAction> actionHandler,
            String error) {
        if (!sameMainHand(player, shownItem)) {
            openHeldItemChanged(player, actionHandler);
            return;
        }
        String label = "商品: " + itemLabel(shownItem) + "\n" + PRICE_INPUT_LABEL;
        if (error != null) {
            label = error + "\n\n" + label;
        }
        CustomForm form = CustomForm.builder()
                .title("その他の出品価格")
                .input(label, "例: 3000")
                .validResultHandler(response -> runOnMain(() -> {
                    String input = response.asInput(0).trim();
                    try {
                        int priceXp = Integer.parseInt(input);
                        if (priceXp <= 0) {
                            throw new NumberFormatException("non-positive");
                        }
                        openSellConfirmation(player, shownItem, priceXp, actionHandler);
                    } catch (NumberFormatException invalidPrice) {
                        openCustomSellPrice(
                                player,
                                shownItem,
                                actionHandler,
                                "価格は1以上の整数（サーバーXP）で入力してください。");
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openSellConfirmation(
            Player player,
            ItemStack shownItem,
            int priceXp,
            Consumer<MarketFormAction> actionHandler) {
        if (!sameMainHand(player, shownItem)) {
            openHeldItemChanged(player, actionHandler);
            return;
        }
        ModalForm form = ModalForm.builder()
                .title("出品内容の最終確認")
                .content("商品: " + itemLabel(shownItem) + "\n合計価格: " + priceLabel(priceXp)
                        + "\n\nこの内容で出品しますか？")
                .button1("この内容で出品")
                .button2("価格を選び直す")
                .validResultHandler(response -> runOnMain(() -> {
                    if (!response.clickedFirst()) {
                        openSellPriceChoices(player, shownItem, actionHandler);
                        return;
                    }
                    if (!sameMainHand(player, shownItem)) {
                        openHeldItemChanged(player, actionHandler);
                        return;
                    }
                    actionHandler.accept(
                            new MarketFormAction(MarketFormAction.Kind.SELL, 0, priceXp));
                }))
                .build();
        sendForm(player, form);
    }

    private void openMine(Player player, Consumer<MarketFormAction> actionHandler) {
        openMine(player, actionHandler, 1);
    }

    private void openMine(
            Player player, Consumer<MarketFormAction> actionHandler, int requestedPage) {
        List<MarketListing> allListings = repository.activeListings().stream()
                .filter(listing -> listing.sellerId().equals(player.getUniqueId()))
                .toList();
        if (allListings.isEmpty()) {
            openInfo(
                    player,
                    "自分の出品",
                    "現在の出品はありません。",
                    () -> open(player, actionHandler));
            return;
        }
        BedrockFormPages.Page<MarketListing> page =
                BedrockFormPages.select(allListings, requestedPage, PAGE_SIZE);
        var builder = SimpleForm.builder()
                .title("自分の出品")
                .content("詳細を確認する商品を選んでください。 "
                        + page.number() + " / " + page.total());
        List<Runnable> handlers = new ArrayList<>();
        page.items().forEach(listing -> {
            builder.button("#" + listing.id() + " "
                    + listing.label() + "\n" + priceLabel(listing.priceXp()));
            handlers.add(() -> openCancelConfirmation(
                    player, listing.id(), actionHandler, page.number(), true));
        });
        if (page.number() > 1) {
            builder.button("前のページ");
            handlers.add(() -> openMine(player, actionHandler, page.number() - 1));
        }
        if (page.number() < page.total()) {
            builder.button("次のページ");
            handlers.add(() -> openMine(player, actionHandler, page.number() + 1));
        }
        builder.button("戻る");
        handlers.add(() -> open(player, actionHandler));
        SimpleForm form = builder
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < handlers.size()) {
                        handlers.get(selected).run();
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openOwnListingFromBrowse(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null
                || listing.status() != MarketListing.Status.ACTIVE
                || !listing.sellerId().equals(player.getUniqueId())) {
            openInfo(
                    player,
                    "自分の出品",
                    "その出品は見つからないか、すでに取引中です。",
                    () -> openListings(player, actionHandler, page));
            return;
        }
        SimpleForm form = SimpleForm.builder()
                .title("自分の出品")
                .content("#" + listing.id() + " " + listing.label()
                        + "\n価格: " + priceLabel(listing.priceXp())
                        + "\n\n自分の出品は購入できません。")
                .button("取り消し内容を確認")
                .button("商品一覧へ戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedButtonId() == 0) {
                        openCancelConfirmation(player, listing.id(), actionHandler, page, false);
                    } else {
                        openListings(player, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openCancelConfirmation(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page,
            boolean returnToMine) {
        MarketListing listing = repository.find(listingId).orElse(null);
        Runnable back = returnToMine
                ? () -> openMine(player, actionHandler, page)
                : () -> openListings(player, actionHandler, page);
        if (listing == null
                || listing.status() != MarketListing.Status.ACTIVE
                || !listing.sellerId().equals(player.getUniqueId())) {
            openInfo(player, "出品取り消し", "その出品は見つからないか、すでに取引中です。", back);
            return;
        }
        ModalForm form = ModalForm.builder()
                .title("出品取り消しの確認")
                .content("#" + listing.id() + " " + listing.label()
                        + "\n価格: " + priceLabel(listing.priceXp())
                        + "\n\n取り消すとアイテムが返却されます。")
                .button1("出品を取り消す")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(new MarketFormAction(
                                MarketFormAction.Kind.CANCEL,
                                listing.id(),
                                listing.priceXp()));
                    } else {
                        back.run();
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openHeldItemChanged(
            Player player, Consumer<MarketFormAction> actionHandler) {
        openInfo(
                player,
                "出品する",
                "価格選択中に手持ちアイテムが変わりました。もう一度出品を開いてください。",
                () -> open(player, actionHandler));
    }

    private void openInfo(Player player, String title, String content, Runnable back) {
        SimpleForm form = SimpleForm.builder()
                .title(title)
                .content(content)
                .button("戻る")
                .validResultHandler(response -> runOnMain(back))
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

    static String priceLabel(int priceXp) {
        return String.format("%,d サーバーXP", priceXp);
    }

    static List<Integer> quickPrices() {
        return QUICK_PRICES;
    }

    private static String itemLabel(ItemStack item) {
        return MarketItems.marketDisplayName(item) + " x" + item.getAmount();
    }

    private static boolean sameMainHand(Player player, ItemStack expected) {
        return expected.equals(player.getInventory().getItemInMainHand());
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
