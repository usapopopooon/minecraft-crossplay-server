package net.usapo.eventbridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class JavaMarketChestMenu implements JavaMarketMenuGateway {
    private static final int PAGE_SIZE = 45;

    private final MarketRepository repository;
    private final JavaChestMenus menus;

    JavaMarketChestMenu(MarketRepository repository, JavaChestMenus menus) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    @Override
    public boolean open(Player player, Consumer<MarketFormAction> actionHandler) {
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(10, JavaChestMenus.action(
                Material.CHEST,
                "商品を見る",
                List.of("出品中の商品を一覧表示します。"),
                () -> openListings(player, actionHandler, 1)));
        entries.put(12, JavaChestMenus.action(
                Material.EMERALD,
                "手に持ったスタックを出品",
                List.of("スタック全体の価格を数字ボタンで指定します。"),
                () -> openSell(player, actionHandler)));
        entries.put(14, JavaChestMenus.action(
                Material.ENDER_CHEST,
                "自分の出品",
                List.of("出品内容の確認や取り消しができます。"),
                () -> openMine(player, actionHandler, 1)));
        entries.put(16, JavaChestMenus.terminalAction(
                Material.EXPERIENCE_BOTTLE,
                "サーバーXP残高",
                List.of("残高は自分だけに表示されます。"),
                () -> actionHandler.accept(action(MarketFormAction.Kind.BALANCE, 0, 0))));
        entries.put(22, JavaChestMenus.terminalAction(
                Material.BARRIER, "閉じる", List.of(), () -> {}));
        return menus.open(player, "プレイヤーマーケット", 27, entries);
    }

    private void openListings(
            Player player, Consumer<MarketFormAction> actionHandler, int requestedPage) {
        List<MarketListing> allListings = repository.activeListings();
        int pages = Math.max(1, (allListings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        allListings.stream()
                .skip((long) (page - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .forEachOrdered(listing -> entries.put(
                        entries.size(),
                        JavaChestMenus.action(
                                listingIcon(listing),
                                listingLore(listing, "クリックして詳細を表示"),
                                () -> openListing(player, listing.id(), actionHandler, page))));
        if (allListings.isEmpty()) {
            entries.put(22, JavaChestMenus.display(
                    Material.BARRIER,
                    "現在出品されている商品はありません",
                    List.of()));
        }
        if (page > 1) {
            entries.put(45, JavaChestMenus.action(
                    Material.ARROW,
                    "前のページ",
                    List.of(),
                    () -> openListings(player, actionHandler, page - 1)));
        }
        entries.put(49, JavaChestMenus.action(
                Material.OAK_DOOR,
                "マーケットへ戻る",
                List.of(),
                () -> open(player, actionHandler)));
        if (page < pages) {
            entries.put(53, JavaChestMenus.action(
                    Material.ARROW,
                    "次のページ",
                    List.of(),
                    () -> openListings(player, actionHandler, page + 1)));
        }
        menus.open(player, "商品一覧 " + page + "/" + pages, 54, entries);
    }

    private void openListing(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null || listing.status() != MarketListing.Status.ACTIVE) {
            player.sendMessage("その商品は見つからないか、すでに売り切れています。");
            openListings(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(13, JavaChestMenus.display(listingIcon(listing), listingLore(listing, "")));
        if (listing.sellerId().equals(player.getUniqueId())) {
            entries.put(15, JavaChestMenus.display(
                    Material.GRAY_DYE,
                    "自分の出品です",
                    List.of("取り消しは「自分の出品」から行えます。")));
        } else {
            entries.put(15, JavaChestMenus.action(
                    Material.LIME_DYE,
                    "購入する",
                    List.of(priceLabel(listing.priceXp())),
                    () -> openBuyConfirmation(player, listing.id(), actionHandler, page)));
        }
        entries.put(18, JavaChestMenus.action(
                Material.ARROW,
                "商品一覧へ戻る",
                List.of(),
                () -> openListings(player, actionHandler, page)));
        menus.open(player, "商品 #" + listing.id(), 27, entries);
    }

    private void openBuyConfirmation(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null || listing.status() != MarketListing.Status.ACTIVE) {
            player.sendMessage("その商品は見つからないか、すでに売り切れています。");
            openListings(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(11, JavaChestMenus.display(listingIcon(listing), listingLore(listing, "")));
        entries.put(15, JavaChestMenus.terminalAction(
                Material.LIME_CONCRETE,
                "購入を確定する",
                List.of(priceLabel(listing.priceXp())),
                () -> actionHandler.accept(action(
                        MarketFormAction.Kind.BUY, listing.id(), listing.priceXp()))));
        entries.put(18, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openListing(player, listing.id(), actionHandler, page)));
        menus.open(player, "購入内容の確認", 27, entries);
    }

    private void openSell(Player player, Consumer<MarketFormAction> actionHandler) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            player.sendMessage("出品するアイテムをメインハンドに持ってください。");
            open(player, actionHandler);
            return;
        }
        ItemStack shownItem = held.clone();
        menus.openNumberInput(
                player,
                "出品価格を入力",
                MarketItems.marketDisplayName(held) + " x" + held.getAmount()
                        + " の合計価格（サーバーXP）",
                1,
                Integer.MAX_VALUE,
                marketIcon(held),
                List.of(),
                true,
                price -> {
                    if (!shownItem.equals(player.getInventory().getItemInMainHand())) {
                        player.sendMessage("価格入力中に手持ちアイテムが変わりました。もう一度出品画面を開いてください。");
                        open(player, actionHandler);
                        return;
                    }
                    actionHandler.accept(action(MarketFormAction.Kind.SELL, 0, price));
                },
                () -> open(player, actionHandler));
    }

    private void openMine(
            Player player, Consumer<MarketFormAction> actionHandler, int requestedPage) {
        List<MarketListing> mine = repository.activeListings().stream()
                .filter(listing -> listing.sellerId().equals(player.getUniqueId()))
                .toList();
        int pages = Math.max(1, (mine.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        mine.stream()
                .skip((long) (page - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .forEachOrdered(listing -> entries.put(
                        entries.size(),
                        JavaChestMenus.action(
                                listingIcon(listing),
                                listingLore(listing, "クリックして取り消し内容を確認"),
                                () -> openCancelConfirmation(
                                        player, listing.id(), actionHandler, page))));
        if (mine.isEmpty()) {
            entries.put(22, JavaChestMenus.display(
                    Material.BARRIER, "現在の出品はありません", List.of()));
        }
        if (page > 1) {
            entries.put(45, JavaChestMenus.action(
                    Material.ARROW,
                    "前のページ",
                    List.of(),
                    () -> openMine(player, actionHandler, page - 1)));
        }
        entries.put(49, JavaChestMenus.action(
                Material.OAK_DOOR,
                "マーケットへ戻る",
                List.of(),
                () -> open(player, actionHandler)));
        if (page < pages) {
            entries.put(53, JavaChestMenus.action(
                    Material.ARROW,
                    "次のページ",
                    List.of(),
                    () -> openMine(player, actionHandler, page + 1)));
        }
        menus.open(player, "自分の出品 " + page + "/" + pages, 54, entries);
    }

    private void openCancelConfirmation(
            Player player,
            long listingId,
            Consumer<MarketFormAction> actionHandler,
            int page) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null
                || listing.status() != MarketListing.Status.ACTIVE
                || !listing.sellerId().equals(player.getUniqueId())) {
            player.sendMessage("その出品は見つからないか、すでに取引中です。");
            openMine(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(11, JavaChestMenus.display(listingIcon(listing), listingLore(listing, "")));
        entries.put(15, JavaChestMenus.terminalAction(
                Material.RED_CONCRETE,
                "出品を取り消す",
                List.of("アイテムは手元へ返却されます。"),
                () -> actionHandler.accept(action(
                        MarketFormAction.Kind.CANCEL, listing.id(), listing.priceXp()))));
        entries.put(18, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openMine(player, actionHandler, page)));
        menus.open(player, "出品取り消しの確認", 27, entries);
    }

    private static List<String> listingLore(MarketListing listing, String action) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("商品: " + listing.label());
        lines.add("価格: " + priceLabel(listing.priceXp()));
        lines.add("出品者: " + listing.sellerName());
        lines.add("出品番号: #" + listing.id());
        if (!action.isBlank()) {
            lines.add(action);
        }
        return lines;
    }

    private static ItemStack listingIcon(MarketListing listing) {
        return marketIcon(listing.item());
    }

    static ItemStack marketIcon(ItemStack original) {
        ItemStack icon = original.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta.hasDisplayName()) {
            return icon;
        }
        meta.displayName(Component.text(MarketItems.marketDisplayName(icon))
                .decoration(TextDecoration.ITALIC, false));
        icon.setItemMeta(meta);
        return icon;
    }

    private static String priceLabel(int priceXp) {
        return String.format("%,d サーバーXP", priceXp);
    }

    private static MarketFormAction action(
            MarketFormAction.Kind kind, long listingId, int priceXp) {
        return new MarketFormAction(kind, listingId, priceXp);
    }
}
