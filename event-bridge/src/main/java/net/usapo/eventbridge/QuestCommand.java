package net.usapo.eventbridge;

import java.io.IOException;
import java.time.Duration;
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

final class QuestCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 6;
    private static final int HISTORY_LIMIT = 200;

    private final QuestRepository repository;
    private final QuestActions actions;
    private final BedrockQuestFormGateway forms;
    private final JavaQuestMenuGateway javaMenus;
    private final NamespacedKey draftKey;
    private final NamespacedKey pendingRewardKey;
    private final NamespacedKey claimHistoryKey;

    QuestCommand(
            QuestRepository repository,
            QuestActions actions,
            BedrockQuestFormGateway forms,
            NamespacedKey draftKey,
            NamespacedKey pendingRewardKey,
            NamespacedKey claimHistoryKey) {
        this(
                repository,
                actions,
                forms,
                (player, handler) -> false,
                draftKey,
                pendingRewardKey,
                claimHistoryKey);
    }

    QuestCommand(
            QuestRepository repository,
            QuestActions actions,
            BedrockQuestFormGateway forms,
            JavaQuestMenuGateway javaMenus,
            NamespacedKey draftKey,
            NamespacedKey pendingRewardKey,
            NamespacedKey claimHistoryKey) {
        this.repository = repository;
        this.actions = actions;
        this.forms = forms;
        this.javaMenus = javaMenus;
        this.draftKey = draftKey;
        this.pendingRewardKey = pendingRewardKey;
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
        deliverNotices(player);
        if (recoverPendingReward(player) || actions.recoverPendingSubmission(player)) {
            return true;
        }
        if (arguments.length == 0) {
            if (!forms.open(player, action -> handleFormAction(player, action))
                    && !javaMenus.open(player, action -> handleFormAction(player, action))) {
                sendUsage(player);
            }
            return true;
        }
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "list" -> showListings(player, parsePage(arguments));
            case "create" -> createDraft(player, arguments);
            case "confirm" -> confirm(player);
            case "discard" -> discardDraft(player);
            case "mine" -> showMine(player);
            case "accept" -> withQuestId(player, arguments, id -> accept(player, id));
            case "submit" -> withQuestId(player, arguments, id -> submit(player, id));
            case "abandon" -> withQuestId(player, arguments, id -> abandon(player, id));
            case "cancel" -> withQuestId(player, arguments, id -> cancel(player, id));
            case "claim" -> claim(player);
            default -> sendUsage(player);
        }
        return true;
    }

    private void handleFormAction(Player player, QuestFormAction action) {
        if (!player.isOnline()) {
            return;
        }
        if (recoverPendingReward(player) || actions.recoverPendingSubmission(player)) {
            return;
        }
        switch (action.kind()) {
            case LIST -> showListings(player, 1);
            case CREATE -> createDraft(player, action.count(), action.hours());
            case CONFIRM -> confirm(player);
            case DISCARD -> discardDraft(player);
            case MINE -> showMine(player);
            case ACCEPT -> accept(player, action.questId());
            case SUBMIT -> submit(player, action.questId());
            case ABANDON -> abandon(player, action.questId());
            case CANCEL -> cancel(player, action.questId());
            case CLAIM -> claim(player);
        }
    }

    private void createDraft(Player player, String[] arguments) {
        Optional<Integer> count = parsePositiveInteger(arguments, 1);
        Optional<Integer> hours = parsePositiveInteger(arguments, 2);
        if (arguments.length != 3 || count.isEmpty() || hours.isEmpty()) {
            player.sendMessage("依頼品を手に持って: /quest create <個数> <期限時間:1〜72>");
            return;
        }
        createDraft(player, count.get(), hours.get());
    }

    private void createDraft(Player player, int count, int hours) {
        ItemStack sample = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSimpleStack(sample)) {
            player.sendMessage("依頼品は、名前・エンチャント等のない通常のスタック可能アイテムを手に持ってください。");
            return;
        }
        if (count > sample.getMaxStackSize()) {
            player.sendMessage("一括納品できる個数（最大 " + sample.getMaxStackSize() + "）を指定してください。");
            return;
        }
        if (hours < 1 || hours > 72) {
            player.sendMessage("納品期限は1〜72時間で指定してください。");
            return;
        }
        QuestDraft draft = new QuestDraft(
                sample.getType().getKey().toString(),
                MarketItems.displayName(sample),
                count,
                hours);
        player.getPersistentDataContainer()
                .set(draftKey, PersistentDataType.STRING, draft.encode());
        try {
            player.saveData();
        } catch (RuntimeException error) {
            player.getPersistentDataContainer().remove(draftKey);
            player.sendMessage("依頼内容を保存できませんでした。");
            return;
        }
        player.sendMessage("依頼品: " + draft.requestedItemName() + " x" + count
                + " / 受注後の期限: " + hours + "時間");
        player.sendMessage("次に報酬にするスタック全部をメインハンドへ持ち、/quest を開いて「公開内容を確認」を選んでください。");
        player.sendMessage("報酬は先に預かります。受注後は依頼者から取り消せません。");
    }

    private void discardDraft(Player player) {
        String encoded = player.getPersistentDataContainer().get(draftKey, PersistentDataType.STRING);
        if (encoded == null) {
            player.sendMessage("破棄するクエストの下書きはありません。");
            return;
        }
        player.getPersistentDataContainer().remove(draftKey);
        try {
            player.saveData();
            player.sendMessage("クエストの下書きを破棄しました。アイテムは消費していません。");
        } catch (RuntimeException error) {
            player.getPersistentDataContainer()
                    .set(draftKey, PersistentDataType.STRING, encoded);
            player.sendMessage("下書きの破棄状態を保存できませんでした。もう一度お試しください。");
        }
    }

    private void confirm(Player player) {
        String encodedDraft = player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING);
        if (encodedDraft == null) {
            player.sendMessage("先に依頼品を手に持って /quest を開き、「依頼を作る」を選んでください。");
            return;
        }
        QuestDraft draft;
        try {
            draft = QuestDraft.decode(encodedDraft);
        } catch (IllegalArgumentException error) {
            player.sendMessage("依頼の下書きが壊れています。/quest を開いて下書きを破棄し、作り直してください。");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSimpleStack(held)) {
            player.sendMessage("報酬は、名前・エンチャント等のない通常のスタック可能アイテムにしてください。");
            return;
        }
        PendingQuestReward pending =
                new PendingQuestReward(UUID.randomUUID(), draft, held.clone());
        player.getPersistentDataContainer()
                .set(pendingRewardKey, PersistentDataType.STRING, pending.encode());
        player.getInventory().setItemInMainHand(null);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            restorePendingReward(player, pending);
            player.sendMessage("報酬を保存できませんでした。アイテムは手元へ戻しました。");
            return;
        }
        QuestListing quest;
        try {
            quest = createFromPending(player, pending);
        } catch (IOException | RuntimeException error) {
            restorePendingReward(player, pending);
            player.sendMessage("クエストを保存できませんでした。報酬は手元へ戻しました。");
            return;
        }
        if (!actions.publishPersisted(quest, "created")) {
            player.sendMessage("クエストは安全に保存しましたが、掲示板への反映待ちです。次回参加時に再試行します。");
            return;
        }
        clearCreationData(player);
        try {
            player.saveData();
        } catch (RuntimeException ignored) {
            // 次回、同じevent IDのクエストを確認して安全に整理する。
        }
        player.sendMessage("クエストを公開しました: " + describe(quest));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        deliverNotices(player);
        if (!recoverPendingReward(player)) {
            actions.recoverPendingSubmission(player);
        }
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        if (claims > 0) {
            player.sendMessage("クエスト受取箱に " + claims + " 件あります。/quest から受け取れます。");
        }
    }

    void deliverNotices(Player player) {
        for (QuestNotice notice : repository.pendingNotices(player.getUniqueId())) {
            player.sendMessage("[クエスト] " + notice.message());
            try {
                repository.acknowledgeNotice(notice.id(), player.getUniqueId());
            } catch (IOException error) {
                player.sendMessage("通知の確認状態を保存できなかったため、次回もう一度表示される場合があります。");
                return;
            }
        }
    }

    boolean recoverPendingReward(Player player) {
        String encoded = player.getPersistentDataContainer()
                .get(pendingRewardKey, PersistentDataType.STRING);
        if (encoded == null) {
            return false;
        }
        try {
            PendingQuestReward pending = PendingQuestReward.decode(encoded);
            QuestListing quest = repository.findByEventId(pending.eventId()).orElseGet(() -> {
                try {
                    return createFromPending(player, pending);
                } catch (IOException error) {
                    throw new QuestRewardRecoveryException(error);
                }
            });
            validatePendingReward(player, pending, quest);
            if (!actions.publishPersisted(quest, "snapshot")) {
                throw new IllegalStateException("quest publication is still pending");
            }
            clearCreationData(player);
            player.saveData();
            player.sendMessage("保存途中だったクエストを復旧しました: #" + quest.id());
        } catch (RuntimeException error) {
            player.sendMessage("クエスト報酬の保護データを復旧できませんでした。管理者へご連絡ください。");
        }
        return true;
    }

    private QuestListing createFromPending(Player player, PendingQuestReward pending)
            throws IOException {
        QuestDraft draft = pending.draft();
        return repository.create(
                pending.eventId(),
                player.getUniqueId(),
                player.getName(),
                draft.requestedItemId(),
                draft.requestedItemName(),
                draft.requestedCount(),
                draft.fulfillmentHours(),
                pending.reward(),
                System.currentTimeMillis());
    }

    private static void validatePendingReward(
            Player player, PendingQuestReward pending, QuestListing quest) {
        QuestDraft draft = pending.draft();
        if (!quest.ownerId().equals(player.getUniqueId())
                || !quest.eventId().equals(pending.eventId())
                || !quest.requestedItemId().equals(draft.requestedItemId())
                || quest.requestedCount() != draft.requestedCount()
                || quest.fulfillmentHours() != draft.fulfillmentHours()
                || !quest.reward().equals(pending.reward())) {
            throw new IllegalStateException("pending reward does not match quest");
        }
    }

    private void restorePendingReward(Player player, PendingQuestReward pending) {
        player.getInventory().setItemInMainHand(pending.reward());
        player.getPersistentDataContainer().remove(pendingRewardKey);
        try {
            player.saveData();
        } catch (RuntimeException ignored) {
            // メモリ上は復元済み。古い保存データにマーカーが残れば再度エスクローとして復旧する。
        }
    }

    private void clearCreationData(Player player) {
        player.getPersistentDataContainer().remove(pendingRewardKey);
        player.getPersistentDataContainer().remove(draftKey);
    }

    private void accept(Player player, long questId) {
        try {
            QuestTransition result = actions.accept(
                    questId,
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    player.getName(),
                    System.currentTimeMillis());
            player.sendMessage("受注しました: " + describe(result.quest()));
            player.sendMessage("納品時は依頼品をメインハンドにまとめて持ち、/quest の「自分の依頼・受注」から納品してください。");
        } catch (QuestActionException error) {
            player.sendMessage(error.getMessage());
        }
    }

    private void submit(Player player, long questId) {
        try {
            QuestTransition result = actions.submit(
                    questId, UUID.randomUUID(), player, System.currentTimeMillis());
            player.sendMessage("納品が完了しました: #" + result.quest().id());
            player.sendMessage("報酬は受取箱へ入りました。/quest から受け取れます。");
        } catch (QuestActionException error) {
            player.sendMessage(error.getMessage());
        }
    }

    private void abandon(Player player, long questId) {
        try {
            actions.abandon(
                    questId,
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    System.currentTimeMillis());
            player.sendMessage("クエストを辞退しました。依頼は再び募集されます: #" + questId);
        } catch (QuestActionException error) {
            player.sendMessage(error.getMessage());
        }
    }

    private void cancel(Player player, long questId) {
        try {
            actions.cancel(questId, UUID.randomUUID(), player.getUniqueId());
            player.sendMessage("クエストを取り消しました。報酬は受取箱へ戻りました。/quest から受け取れます。");
        } catch (QuestActionException error) {
            player.sendMessage(error.getMessage());
        }
    }

    private void claim(Player player) {
        List<QuestClaim> pending = repository.pendingClaims(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage("クエスト受取箱は空です。");
            return;
        }
        int delivered = 0;
        List<String> blocked = new ArrayList<>();
        for (QuestClaim claim : pending) {
            ClaimDelivery result = deliverClaim(player, claim);
            if (result == ClaimDelivery.NO_SPACE) {
                ItemStack item = claim.item();
                blocked.add(MarketItems.displayName(item) + " x" + item.getAmount());
                continue;
            }
            if (result == ClaimDelivery.FAILED) {
                break;
            }
            delivered++;
        }
        int remaining = repository.pendingClaims(player.getUniqueId()).size();
        if (delivered > 0) {
            player.sendMessage("クエスト受取箱から " + delivered + " 件受け取りました。");
        }
        if (remaining > 0) {
            if (!blocked.isEmpty()) {
                player.sendMessage("空き不足で受け取れなかったもの: " + summarize(blocked));
            }
            player.sendMessage("受取箱に残り " + remaining + " 件です。空きを作って /quest から再度受け取ってください。");
        }
    }

    private static String summarize(List<String> labels) {
        int shown = Math.min(3, labels.size());
        String summary = String.join("、", labels.subList(0, shown));
        return labels.size() > shown ? summary + "、ほか" + (labels.size() - shown) + "件" : summary;
    }

    private ClaimDelivery deliverClaim(Player player, QuestClaim claim) {
        UUID transferId = claim.transferId() == null ? UUID.randomUUID() : claim.transferId();
        String history = readHistory(player);
        if (containsTransfer(history, transferId)) {
            try {
                player.saveData();
                repository.completeClaim(claim.id(), player.getUniqueId(), transferId);
                return ClaimDelivery.DELIVERED;
            } catch (IOException | RuntimeException error) {
                player.sendMessage("受取状態を保存できませんでした。少し待って再実行してください。");
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
            writeHistory(player, history, transferId);
        } catch (IOException | RuntimeException error) {
            inventory.setStorageContents(snapshot);
            restoreHistory(player, history);
            try {
                repository.abortClaim(claim.id(), player.getUniqueId(), transferId);
            } catch (IOException ignored) {
                // 同じtransfer IDで再試行できる。
            }
            player.sendMessage("受取処理を保存できませんでした。少し待って再実行してください。");
            return ClaimDelivery.FAILED;
        }
        try {
            player.saveData();
        } catch (RuntimeException error) {
            player.sendMessage("受取処理を保存中です。同じコマンドを再実行してください。");
            return ClaimDelivery.FAILED;
        }
        try {
            repository.completeClaim(claim.id(), player.getUniqueId(), transferId);
            return ClaimDelivery.DELIVERED;
        } catch (IOException error) {
            player.sendMessage("受取確定を再試行します。同じコマンドをもう一度実行してください。");
            return ClaimDelivery.FAILED;
        }
    }

    private String readHistory(Player player) {
        return player.getPersistentDataContainer().get(claimHistoryKey, PersistentDataType.STRING);
    }

    private static boolean containsTransfer(String history, UUID transferId) {
        return history != null
                && !history.isBlank()
                && Arrays.asList(history.split("\\n")).contains(transferId.toString());
    }

    private void writeHistory(Player player, String history, UUID transferId) {
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

    private void restoreHistory(Player player, String history) {
        if (history == null) {
            player.getPersistentDataContainer().remove(claimHistoryKey);
        } else {
            player.getPersistentDataContainer()
                    .set(claimHistoryKey, PersistentDataType.STRING, history);
        }
    }

    private void showListings(Player player, int page) {
        List<QuestListing> quests = repository.openQuests();
        if (quests.isEmpty()) {
            player.sendMessage("現在募集中のクエストはありません。");
            return;
        }
        int pages = Math.max(1, (quests.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selected = Math.min(Math.max(1, page), pages);
        player.sendMessage("クエスト掲示板 " + selected + "/" + pages);
        quests.stream()
                .skip((long) (selected - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .forEach(quest -> player.sendMessage(describe(quest)));
        player.sendMessage("受注: /quest accept <クエスト番号>");
        if (selected < pages) {
            player.sendMessage("次ページ: /quest list " + (selected + 1));
        }
        if (selected > 1) {
            player.sendMessage("前ページ: /quest list " + (selected - 1));
        }
    }

    private void showMine(Player player) {
        List<QuestListing> quests = repository.activeFor(player.getUniqueId());
        List<QuestClaim> claims = repository.pendingClaims(player.getUniqueId());
        if (quests.isEmpty() && claims.isEmpty()) {
            player.sendMessage("進行中のクエストも受取品もありません。");
            return;
        }
        player.sendMessage("自分のクエスト");
        for (QuestListing quest : quests) {
            if (quest.ownerId().equals(player.getUniqueId())) {
                String status = quest.status() == QuestListing.Status.OPEN
                        ? "依頼中・募集中"
                        : "依頼中・" + quest.workerName() + " が受注中";
                player.sendMessage(describe(quest) + " / " + status);
            } else {
                player.sendMessage(describe(quest) + " / 受注中・残り "
                        + remaining(quest.acceptedDeadlineMillis()));
            }
        }
        if (!claims.isEmpty()) {
            player.sendMessage("受取箱: " + claims.size() + "件 /quest claim");
        }
        player.sendMessage("納品: /quest submit <番号> / 辞退: /quest abandon <番号>");
        player.sendMessage("募集中の依頼取消: /quest cancel <番号>");
    }

    private static String describe(QuestListing quest) {
        return "#" + quest.id() + " " + quest.requestedLabel() + " → 報酬 "
                + quest.rewardLabel() + " / " + quest.fulfillmentHours() + "時間 / "
                + quest.ownerName();
    }

    private static String remaining(long deadlineMillis) {
        long millis = Math.max(0, deadlineMillis - System.currentTimeMillis());
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return hours + "時間" + minutes + "分";
    }

    private static void withQuestId(Player player, String[] arguments, QuestIdAction action) {
        Optional<Long> questId = parsePositiveLong(arguments, 1);
        if (arguments.length != 2 || questId.isEmpty()) {
            player.sendMessage("クエスト番号を指定してください。");
            return;
        }
        action.run(questId.get());
    }

    private static Optional<Integer> parsePositiveInteger(String[] arguments, int index) {
        if (arguments.length <= index) {
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
        if (arguments.length <= index) {
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
        player.sendMessage("クエスト掲示板: /quest list [ページ]");
        player.sendMessage("作成1: 依頼品を手に持ち /quest create <個数> <期限時間:1〜72>");
        player.sendMessage("作成2: 報酬スタックを手に持ち /quest confirm");
        player.sendMessage("下書きを破棄: /quest discard");
        player.sendMessage("自分の依頼・受注・受取箱: /quest mine");
        player.sendMessage("受注: /quest accept <番号> / 納品: /quest submit <番号>");
        player.sendMessage("辞退: /quest abandon <番号> / 依頼取消: /quest cancel <番号>");
        player.sendMessage("受取箱: /quest claim");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of(
                            "list", "create", "confirm", "discard", "mine", "accept", "submit",
                            "abandon", "cancel", "claim")
                    .stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @FunctionalInterface
    private interface QuestIdAction {
        void run(long questId);
    }

    private enum ClaimDelivery {
        DELIVERED,
        NO_SPACE,
        FAILED
    }

    private static final class QuestRewardRecoveryException extends RuntimeException {
        private QuestRewardRecoveryException(IOException cause) {
            super(cause);
        }
    }
}
