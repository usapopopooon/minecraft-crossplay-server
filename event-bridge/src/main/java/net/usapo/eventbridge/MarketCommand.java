package net.usapo.eventbridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

final class MarketCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 8;
    private static final int HISTORY_LIMIT = 100;

    private final MarketRepository repository;
    private final MarketRequestSink requests;
    private final BedrockMarketFormGateway forms;
    private final JavaMarketMenuGateway javaMenus;
    private final NamespacedKey pendingEscrowKey;
    private final NamespacedKey claimHistoryKey;

    MarketCommand(
            MarketRepository repository,
            MarketRequestSink requests,
            BedrockMarketFormGateway forms,
            NamespacedKey pendingEscrowKey) {
        this(
                repository,
                requests,
                forms,
                (player, handler) -> false,
                pendingEscrowKey,
                pendingEscrowKey);
    }

    MarketCommand(
            MarketRepository repository,
            MarketRequestSink requests,
            BedrockMarketFormGateway forms,
            JavaMarketMenuGateway javaMenus,
            NamespacedKey pendingEscrowKey) {
        this(repository, requests, forms, javaMenus, pendingEscrowKey, pendingEscrowKey);
    }

    MarketCommand(
            MarketRepository repository,
            MarketRequestSink requests,
            BedrockMarketFormGateway forms,
            JavaMarketMenuGateway javaMenus,
            NamespacedKey pendingEscrowKey,
            NamespacedKey claimHistoryKey) {
        this.repository = repository;
        this.requests = requests;
        this.forms = forms;
        this.javaMenus = javaMenus;
        this.pendingEscrowKey = pendingEscrowKey;
        this.claimHistoryKey = claimHistoryKey;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはゲーム内のプレイヤーだけが使用できます。");
            return true;
        }
        if (recoverPendingEscrow(player)) {
            return true;
        }
        if (arguments.length == 0) {
            if (!forms.open(player, action -> handleFormAction(player, action))
                    && !javaMenus.open(player, action -> handleFormAction(player, action))) {
                sendUsage(player);
            }
            return true;
        }
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "sell" -> parsePositiveInteger(arguments, 1)
                    .ifPresentOrElse(
                            price -> sell(player, price),
                            () -> player.sendMessage(
                                    "出品: /market sell <合計価格>（価格はサーバーXP）"));
            case "list" -> showListings(player, parsePage(arguments));
            case "mine" -> showMine(player);
            case "buy" -> parsePositiveLong(arguments, 1)
                    .ifPresentOrElse(
                            id -> buy(player, id),
                            () -> player.sendMessage("購入: /market buy <出品番号>"));
            case "cancel" -> parsePositiveLong(arguments, 1)
                    .ifPresentOrElse(
                            id -> cancel(player, id),
                            () -> player.sendMessage("出品取消: /market cancel <出品番号>"));
            case "claim" -> claim(player);
            case "balance" -> requests.publishRequest("balance", 0, 0, player);
            default -> sendUsage(player);
        }
        return true;
    }

    private void handleFormAction(Player player, MarketFormAction action) {
        if (!player.isOnline()) {
            return;
        }
        if (recoverPendingEscrow(player)) {
            return;
        }
        switch (action.kind()) {
            case SELL -> sell(player, action.priceXp());
            case BUY -> buy(player, action.listingId());
            case CANCEL -> cancel(player, action.listingId());
            case CLAIM -> claim(player);
            case BALANCE -> requests.publishRequest("balance", 0, 0, player);
            case LIST -> showListings(player, 1);
            case MINE -> showMine(player);
        }
    }

    private void sell(Player player, int priceXp) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            player.sendMessage("出品するアイテムをメインハンドに持ってください。");
            return;
        }
        ItemStack escrow = held.clone();
        PendingMarketEscrow pending = new PendingMarketEscrow(UUID.randomUUID(), priceXp, escrow);
        player.getPersistentDataContainer()
                .set(pendingEscrowKey, PersistentDataType.STRING, pending.encode());
        player.getInventory().setItemInMainHand(null);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            restorePendingEscrow(player, pending);
            player.sendMessage("出品を保存できませんでした。アイテムは手元へ戻しました。");
            return;
        }
        MarketListing listing;
        try {
            listing = repository.create(
                    pending.eventId(),
                    player.getUniqueId(),
                    player.getName(),
                    pending.priceXp(),
                    pending.item());
        } catch (IOException | RuntimeException error) {
            restorePendingEscrow(player, pending);
            player.sendMessage("出品を保存できませんでした。アイテムは手元へ戻しました。");
            return;
        }
        try {
            requests.publishListing(listing);
        } catch (RuntimeException error) {
            player.sendMessage(
                    "出品は安全に保存しましたが、一覧への反映待ちです。次回参加時に自動再試行します。");
            return;
        }
        clearPendingEscrow(player);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            player.sendMessage("出品しました。復旧用データの整理は次回参加時に自動再試行します。");
            return;
        }
        player.sendMessage("出品しました: #" + listing.id() + " " + listing.label()
                + " / 合計 " + listing.priceXp() + " サーバーXP");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        recoverPendingEscrow(player);
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        if (claims > 0) {
            player.sendMessage("フリマ返却受取箱に " + claims + " 件あります。/market から受け取れます。");
        }
    }

    boolean recoverPendingEscrow(Player player) {
        String encoded = player.getPersistentDataContainer()
                .get(pendingEscrowKey, PersistentDataType.STRING);
        if (encoded == null) {
            return false;
        }
        try {
            PendingMarketEscrow pending = PendingMarketEscrow.decode(encoded);
            MarketListing listing = repository.findByEventId(pending.eventId()).orElseGet(() -> {
                try {
                    return repository.create(
                            pending.eventId(),
                            player.getUniqueId(),
                            player.getName(),
                            pending.priceXp(),
                            pending.item());
                } catch (IOException error) {
                    throw new MarketEscrowRecoveryException(error);
                }
            });
            if (!listing.sellerId().equals(player.getUniqueId())
                    || listing.priceXp() != pending.priceXp()
                    || !listing.item().equals(pending.item())) {
                throw new IllegalStateException("pending market escrow does not match listing");
            }
            requests.publishListing(listing);
            clearPendingEscrow(player);
            player.saveData();
            player.sendMessage("保存途中だったマーケット出品を復旧しました: #" + listing.id());
        } catch (RuntimeException error) {
            player.sendMessage(
                    "保存途中のマーケット出品を復旧できませんでした。アイテム保護データは保持しています。管理者へご連絡ください。");
        }
        return true;
    }

    private void restorePendingEscrow(Player player, PendingMarketEscrow pending) {
        player.getInventory().setItemInMainHand(pending.item());
        clearPendingEscrow(player);
        try {
            player.saveData();
        } catch (RuntimeException ignored) {
            // メモリ上は復元済み。保存済みマーカーが残れば次回参加時にエスクローへ復旧する。
        }
    }

    private void clearPendingEscrow(Player player) {
        player.getPersistentDataContainer().remove(pendingEscrowKey);
    }

    private static final class MarketEscrowRecoveryException extends RuntimeException {
        private MarketEscrowRecoveryException(IOException cause) {
            super(cause);
        }
    }

    private void buy(Player player, long listingId) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null || listing.status() != MarketListing.Status.ACTIVE) {
            player.sendMessage("その商品は見つからないか、すでに売り切れています。");
            return;
        }
        if (listing.sellerId().equals(player.getUniqueId())) {
            player.sendMessage("自分の出品は購入できません。取消は /market cancel を使ってください。");
            return;
        }
        requests.publishRequest("buy", listing.id(), listing.priceXp(), player);
        player.sendMessage("購入を確認しています: #" + listing.id() + " " + listing.label()
                + " / " + listing.priceXp() + " サーバーXP");
    }

    private void cancel(Player player, long listingId) {
        MarketListing listing = repository.find(listingId).orElse(null);
        if (listing == null || listing.status() != MarketListing.Status.ACTIVE) {
            player.sendMessage("その出品は見つからないか、すでに取引中です。");
            return;
        }
        if (!listing.sellerId().equals(player.getUniqueId())) {
            player.sendMessage("取り消せるのは自分の出品だけです。");
            return;
        }
        requests.publishRequest("cancel", listing.id(), listing.priceXp(), player);
        player.sendMessage("出品取消を受け付けました。アイテム返却まで少しお待ちください。");
    }

    private void claim(Player player) {
        List<MarketClaim> pending = repository.pendingClaims(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage("フリマ返却受取箱は空です。");
            return;
        }
        int delivered = 0;
        List<String> blocked = new ArrayList<>();
        for (MarketClaim claim : pending) {
            ClaimDelivery result = deliverClaim(player, claim);
            if (result == ClaimDelivery.NO_SPACE) {
                ItemStack item = claim.item();
                blocked.add(MarketItems.marketDisplayName(item) + " x" + item.getAmount());
                continue;
            }
            if (result == ClaimDelivery.FAILED) {
                break;
            }
            delivered++;
        }
        int remaining = repository.pendingClaims(player.getUniqueId()).size();
        if (delivered > 0) {
            player.sendMessage("フリマ返却受取箱から " + delivered + " 件受け取りました。");
        }
        if (remaining > 0) {
            if (!blocked.isEmpty()) {
                player.sendMessage("空き不足で受け取れなかったもの: " + summarize(blocked));
            }
            player.sendMessage("返却受取箱に残り " + remaining + " 件です。空きを作って /market から再度受け取ってください。");
        }
    }

    private ClaimDelivery deliverClaim(Player player, MarketClaim claim) {
        UUID transferId = claim.transferId() == null ? UUID.randomUUID() : claim.transferId();
        String history = readClaimHistory(player);
        if (containsTransfer(history, transferId)) {
            try {
                player.saveData();
                repository.completeClaim(claim.id(), player.getUniqueId(), transferId);
                return ClaimDelivery.DELIVERED;
            } catch (IOException | RuntimeException error) {
                player.sendMessage("返却受取状態を保存できませんでした。少し待って再実行してください。");
                return ClaimDelivery.FAILED;
            }
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack item = claim.item();
        if (!MarketItems.canFit(inventory, item)) {
            return ClaimDelivery.NO_SPACE;
        }
        ItemStack[] snapshot = MarketItems.snapshot(inventory);
        try {
            repository.prepareClaim(claim.id(), player.getUniqueId(), transferId);
            Map<Integer, ItemStack> leftover = inventory.addItem(item);
            if (!leftover.isEmpty()) {
                inventory.setStorageContents(snapshot);
                repository.abortClaim(claim.id(), player.getUniqueId(), transferId);
                return ClaimDelivery.NO_SPACE;
            }
            writeClaimHistory(player, history, transferId);
        } catch (IOException | RuntimeException error) {
            inventory.setStorageContents(snapshot);
            restoreClaimHistory(player, history);
            try {
                repository.abortClaim(claim.id(), player.getUniqueId(), transferId);
            } catch (IOException ignored) {
                // 同じtransfer IDで再試行できる。
            }
            player.sendMessage("返却受取処理を保存できませんでした。少し待って再実行してください。");
            return ClaimDelivery.FAILED;
        }
        try {
            player.saveData();
        } catch (RuntimeException error) {
            player.sendMessage("返却受取処理を保存中です。同じ操作を再実行してください。");
            return ClaimDelivery.FAILED;
        }
        try {
            repository.completeClaim(claim.id(), player.getUniqueId(), transferId);
            return ClaimDelivery.DELIVERED;
        } catch (IOException | RuntimeException error) {
            player.sendMessage("返却受取の確定を再試行します。同じ操作をもう一度実行してください。");
            return ClaimDelivery.FAILED;
        }
    }

    private String readClaimHistory(Player player) {
        return player.getPersistentDataContainer()
                .get(claimHistoryKey, PersistentDataType.STRING);
    }

    private static boolean containsTransfer(String history, UUID transferId) {
        return history != null
                && !history.isBlank()
                && Arrays.asList(history.split("\\n")).contains(transferId.toString());
    }

    private void writeClaimHistory(Player player, String history, UUID transferId) {
        List<String> entries = new ArrayList<>();
        entries.add(transferId.toString());
        if (history != null && !history.isBlank()) {
            entries.addAll(Arrays.asList(history.split("\\n")));
        }
        if (entries.size() > HISTORY_LIMIT) {
            entries = entries.subList(0, HISTORY_LIMIT);
        }
        player.getPersistentDataContainer()
                .set(claimHistoryKey, PersistentDataType.STRING, String.join("\n", entries));
    }

    private void restoreClaimHistory(Player player, String history) {
        if (history == null) {
            player.getPersistentDataContainer().remove(claimHistoryKey);
        } else {
            player.getPersistentDataContainer()
                    .set(claimHistoryKey, PersistentDataType.STRING, history);
        }
    }

    private static String summarize(List<String> labels) {
        int shown = Math.min(3, labels.size());
        String summary = String.join("、", labels.subList(0, shown));
        return labels.size() > shown ? summary + "、ほか" + (labels.size() - shown) + "件" : summary;
    }

    private void showListings(Player player, int page) {
        List<MarketListing> active = repository.activeListings();
        if (active.isEmpty()) {
            player.sendMessage("現在出品されている商品はありません。");
            return;
        }
        int pages = Math.max(1, (active.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selected = Math.min(Math.max(1, page), pages);
        player.sendMessage("マーケット商品一覧 " + selected + "/" + pages);
        active.stream()
                .skip((long) (selected - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .forEach(listing -> player.sendMessage("#" + listing.id() + " "
                        + listing.label() + " / " + listing.priceXp() + " サーバーXP / "
                        + listing.sellerName()));
        player.sendMessage("購入: /market buy <出品番号>");
    }

    private void showMine(Player player) {
        List<MarketListing> mine = repository.activeListings().stream()
                .filter(listing -> listing.sellerId().equals(player.getUniqueId()))
                .toList();
        if (mine.isEmpty()) {
            player.sendMessage("現在の出品はありません。");
            return;
        }
        player.sendMessage("自分の出品");
        mine.forEach(listing -> player.sendMessage("#" + listing.id() + " "
                + listing.label() + " / " + listing.priceXp() + " サーバーXP"));
        player.sendMessage("取消: /market cancel <出品番号>");
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        if (claims > 0) {
            player.sendMessage("返却受取箱: " + claims + "件 /market claim");
        }
    }

    private static Optional<Integer> parsePositiveInteger(String[] arguments, int index) {
        if (arguments.length != index + 1) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(arguments[index]);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private static Optional<Long> parsePositiveLong(String[] arguments, int index) {
        if (arguments.length != index + 1) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(arguments[index]);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private static int parsePage(String[] arguments) {
        return parsePositiveInteger(arguments, 1).orElse(1);
    }

    private static void sendUsage(Player player) {
        player.sendMessage("マーケット: /market list [ページ]");
        player.sendMessage(
                "出品: /market sell <合計価格>（価格はサーバーXP・手に持ったスタック全部）");
        player.sendMessage("購入: /market buy <出品番号>");
        player.sendMessage("自分の出品: /market mine");
        player.sendMessage("取消: /market cancel <出品番号>");
        player.sendMessage("返却受取箱: /market claim");
        player.sendMessage("サーバーXP残高: /market balance");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("list", "sell", "buy", "mine", "cancel", "claim", "balance").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private enum ClaimDelivery {
        DELIVERED,
        NO_SPACE,
        FAILED
    }
}
