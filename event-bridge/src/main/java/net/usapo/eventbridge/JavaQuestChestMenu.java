package net.usapo.eventbridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class JavaQuestChestMenu implements JavaQuestMenuGateway {
    private static final int LIST_PAGE_SIZE = 45;
    private static final int MINE_PAGE_SIZE = 36;

    private final QuestRepository repository;
    private final JavaChestMenus menus;
    private final NamespacedKey draftKey;

    JavaQuestChestMenu(
            QuestRepository repository, JavaChestMenus menus, NamespacedKey draftKey) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.draftKey = Objects.requireNonNull(draftKey, "draftKey");
    }

    @Override
    public boolean open(Player player, Consumer<QuestFormAction> actionHandler) {
        String encodedDraft = player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING);
        QuestDraft draft = decodeDraft(encodedDraft);
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(10, JavaChestMenus.action(
                Material.WRITABLE_BOOK,
                "募集中の依頼を見る",
                List.of("依頼品・報酬・期限を画面で確認できます。"),
                () -> openListings(player, actionHandler, 1)));
        entries.put(12, JavaChestMenus.action(
                Material.CRAFTING_TABLE,
                "依頼を作る",
                List.of("依頼品をメインハンドに持ってください。"),
                () -> openCreate(player, actionHandler)));
        entries.put(14, JavaChestMenus.action(
                Material.ENDER_CHEST,
                "自分の依頼・受注",
                List.of("取り消し・納品・辞退ができます。"),
                () -> openMine(player, actionHandler, 1)));
        if (claims > 0) {
            entries.put(16, JavaChestMenus.terminalAction(
                    Material.CHEST,
                    "受取箱を受け取る",
                    List.of("現在 " + claims + " 件あります。"),
                    () -> actionHandler.accept(action(QuestFormAction.Kind.CLAIM, 0))));
        } else {
            entries.put(16, JavaChestMenus.display(
                    Material.GRAY_DYE,
                    "受取箱は空です",
                    List.of("現在0件です。")));
        }
        if (draft != null) {
            entries.put(20, JavaChestMenus.action(
                    requestedIcon(draft),
                    List.of(
                            "公開待ち: " + draft.requestedItemName() + " x"
                                    + draft.requestedCount(),
                            "期限: " + draft.fulfillmentHours() + "時間",
                            "報酬をメインハンドに持って確認してください。"),
                    () -> openPublicationConfirmation(player, actionHandler)));
        }
        if (encodedDraft != null) {
            entries.put(24, JavaChestMenus.action(
                    Material.RED_DYE,
                    "下書きを破棄",
                    List.of("アイテムは消費しません。"),
                    () -> openDiscardConfirmation(player, actionHandler)));
        }
        entries.put(22, JavaChestMenus.terminalAction(
                Material.BARRIER, "閉じる", List.of(), () -> {}));
        return menus.open(player, "ギルド・クエスト掲示板", 27, entries);
    }

    private void openCreate(Player player, Consumer<QuestFormAction> actionHandler) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSimpleStack(held)) {
            player.sendMessage("依頼品は、名前・エンチャント等のない通常のスタック可能アイテムを手に持ってください。");
            open(player, actionHandler);
            return;
        }
        openRequestedCount(player, held, actionHandler);
    }

    private void openRequestedCount(
            Player player,
            ItemStack sample,
            Consumer<QuestFormAction> actionHandler) {
        ItemStack shownItem = sample.clone();
        menus.openNumberInput(
                player,
                "依頼する個数",
                MarketItems.displayName(sample) + " の依頼数",
                1,
                sample.getMaxStackSize(),
                inputItemIcon(sample, 0),
                List.of(1, 16, 32, 64),
                false,
                count -> openFulfillmentHours(player, shownItem, count, actionHandler),
                () -> open(player, actionHandler));
    }

    private void openFulfillmentHours(
            Player player,
            ItemStack sample,
            int count,
            Consumer<QuestFormAction> actionHandler) {
        menus.openNumberInput(
                player,
                "納品期限",
                MarketItems.displayName(sample) + " x" + count + " / 受注後の納品期限（時間）",
                1,
                72,
                inputItemIcon(sample, count),
                List.of(6, 12, 24, 48, 72),
                true,
                hours -> {
                    if (!sample.equals(player.getInventory().getItemInMainHand())) {
                        player.sendMessage("入力中に依頼品が変わりました。もう一度依頼作成を開いてください。");
                        open(player, actionHandler);
                        return;
                    }
                    actionHandler.accept(
                            new QuestFormAction(QuestFormAction.Kind.CREATE, 0, count, hours));
                },
                () -> openRequestedCount(player, sample, actionHandler));
    }

    private void openPublicationConfirmation(
            Player player, Consumer<QuestFormAction> actionHandler) {
        QuestDraft draft = decodeDraft(player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING));
        if (draft == null) {
            player.sendMessage("公開できる下書きがありません。依頼を作り直してください。");
            open(player, actionHandler);
            return;
        }
        ItemStack reward = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSimpleStack(reward)) {
            player.sendMessage("報酬にする通常アイテムのスタックをメインハンドへ持ってください。");
            open(player, actionHandler);
            return;
        }
        ItemStack shownReward = reward.clone();
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(11, JavaChestMenus.display(
                requestedIcon(draft),
                List.of(
                        "依頼品: " + draft.requestedItemName() + " x" + draft.requestedCount(),
                        "受注後の期限: " + draft.fulfillmentHours() + "時間")));
        entries.put(15, JavaChestMenus.display(
                rewardIcon(reward),
                List.of("報酬: " + MarketItems.displayName(reward) + " x" + reward.getAmount())));
        entries.put(22, JavaChestMenus.terminalAction(
                Material.LIME_CONCRETE,
                "報酬を預けて公開",
                List.of("表示中の報酬スタック全部を預けます。"),
                () -> {
                    if (!shownReward.equals(player.getInventory().getItemInMainHand())) {
                        player.sendMessage("確認中に報酬が変わりました。現在の手持ちで内容を確認し直してください。");
                        openPublicationConfirmation(player, actionHandler);
                        return;
                    }
                    actionHandler.accept(action(QuestFormAction.Kind.CONFIRM, 0));
                }));
        entries.put(18, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> open(player, actionHandler)));
        menus.open(player, "公開内容の最終確認", 27, entries);
    }

    private void openDiscardConfirmation(
            Player player, Consumer<QuestFormAction> actionHandler) {
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(11, JavaChestMenus.terminalAction(
                Material.RED_CONCRETE,
                "下書きを破棄する",
                List.of("依頼品や報酬は消費しません。"),
                () -> actionHandler.accept(action(QuestFormAction.Kind.DISCARD, 0))));
        entries.put(15, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> open(player, actionHandler)));
        menus.open(player, "下書き破棄の確認", 27, entries);
    }

    private void openListings(
            Player player, Consumer<QuestFormAction> actionHandler, int requestedPage) {
        List<QuestListing> allQuests = repository.openQuests();
        int pages = Math.max(1, (allQuests.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        allQuests.stream()
                .skip((long) (page - 1) * LIST_PAGE_SIZE)
                .limit(LIST_PAGE_SIZE)
                .forEachOrdered(quest -> entries.put(
                        entries.size(),
                        JavaChestMenus.action(
                                requestedIcon(quest),
                                questLore(quest, "クリックして詳細を表示"),
                                () -> openListing(player, quest.id(), actionHandler, page))));
        if (allQuests.isEmpty()) {
            entries.put(22, JavaChestMenus.display(
                    Material.BARRIER, "現在募集中のクエストはありません", List.of()));
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
                "掲示板へ戻る",
                List.of(),
                () -> open(player, actionHandler)));
        if (page < pages) {
            entries.put(53, JavaChestMenus.action(
                    Material.ARROW,
                    "次のページ",
                    List.of(),
                    () -> openListings(player, actionHandler, page + 1)));
        }
        menus.open(player, "募集中のクエスト " + page + "/" + pages, 54, entries);
    }

    private void openListing(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = repository.find(questId).orElse(null);
        if (quest == null || quest.status() != QuestListing.Status.OPEN) {
            player.sendMessage("そのクエストは見つからないか、すでに受注されています。");
            openListings(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        if (quest.ownerId().equals(player.getUniqueId())) {
            entries.put(31, JavaChestMenus.display(
                    Material.GRAY_DYE,
                    "自分の依頼です",
                    List.of("取り消しは「自分の依頼・受注」から行えます。")));
        } else {
            entries.put(31, JavaChestMenus.action(
                    Material.LIME_CONCRETE,
                    "受注内容を確認",
                    List.of(),
                    () -> openAcceptConfirmation(player, quest.id(), actionHandler, page)));
        }
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "一覧へ戻る",
                List.of(),
                () -> openListings(player, actionHandler, page)));
        menus.open(player, "クエスト #" + quest.id(), 45, entries);
    }

    private void openAcceptConfirmation(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = repository.find(questId).orElse(null);
        if (quest == null || quest.status() != QuestListing.Status.OPEN) {
            player.sendMessage("そのクエストは見つからないか、すでに受注されています。");
            openListings(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        entries.put(31, JavaChestMenus.terminalAction(
                Material.LIME_CONCRETE,
                "受注する",
                List.of("受注後 " + quest.fulfillmentHours() + "時間以内に一括納品します。"),
                () -> actionHandler.accept(action(QuestFormAction.Kind.ACCEPT, quest.id()))));
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openListing(player, quest.id(), actionHandler, page)));
        menus.open(player, "クエスト受注の確認", 45, entries);
    }

    private void openMine(
            Player player, Consumer<QuestFormAction> actionHandler, int requestedPage) {
        List<QuestListing> allQuests = repository.activeFor(player.getUniqueId());
        int pages = Math.max(1, (allQuests.size() + MINE_PAGE_SIZE - 1) / MINE_PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        allQuests.stream()
                .skip((long) (page - 1) * MINE_PAGE_SIZE)
                .limit(MINE_PAGE_SIZE)
                .forEachOrdered(quest -> entries.put(
                        entries.size(),
                        JavaChestMenus.action(
                                requestedIcon(quest),
                                mineLore(player, quest),
                                () -> openMineAction(player, quest.id(), actionHandler, page))));
        if (allQuests.isEmpty()) {
            entries.put(22, JavaChestMenus.display(
                    Material.BARRIER, "進行中の依頼・受注はありません", List.of()));
        }
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        if (claims > 0) {
            entries.put(47, JavaChestMenus.terminalAction(
                    Material.CHEST,
                    "受取箱を受け取る",
                    List.of("現在 " + claims + " 件あります。"),
                    () -> actionHandler.accept(action(QuestFormAction.Kind.CLAIM, 0))));
        } else {
            entries.put(47, JavaChestMenus.display(
                    Material.GRAY_DYE,
                    "受取箱は空です",
                    List.of("現在0件です。")));
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
                "掲示板へ戻る",
                List.of(),
                () -> open(player, actionHandler)));
        if (page < pages) {
            entries.put(53, JavaChestMenus.action(
                    Material.ARROW,
                    "次のページ",
                    List.of(),
                    () -> openMine(player, actionHandler, page + 1)));
        }
        menus.open(player, "自分の依頼・受注 " + page + "/" + pages, 54, entries);
    }

    private void openMineAction(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = repository.find(questId).orElse(null);
        if (quest == null
                || (quest.status() != QuestListing.Status.OPEN
                        && quest.status() != QuestListing.Status.ACCEPTED)) {
            player.sendMessage("そのクエストは見つからないか、すでに終了しています。");
            openMine(player, actionHandler, page);
            return;
        }
        boolean owner = quest.ownerId().equals(player.getUniqueId());
        boolean worker = player.getUniqueId().equals(quest.workerId());
        if (!owner && !worker) {
            player.sendMessage("このクエストは自分の依頼・受注ではありません。");
            openMine(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        if (owner && quest.status() == QuestListing.Status.OPEN) {
            entries.put(30, JavaChestMenus.action(
                    Material.RED_CONCRETE,
                    "依頼を取り消す",
                    List.of("報酬は受取箱へ戻ります。"),
                    () -> openCancelConfirmation(player, quest.id(), actionHandler, page)));
        } else if (worker && quest.status() == QuestListing.Status.ACCEPTED) {
            entries.put(29, JavaChestMenus.action(
                    Material.LIME_CONCRETE,
                    "納品内容を確認",
                    List.of("依頼品をメインハンドに必要数以上まとめて持ってください。"),
                    () -> openSubmitConfirmation(player, quest.id(), actionHandler, page)));
            entries.put(33, JavaChestMenus.action(
                    Material.RED_DYE,
                    "辞退内容を確認",
                    List.of("依頼は再び募集されます。"),
                    () -> openAbandonConfirmation(player, quest.id(), actionHandler, page)));
        } else {
            entries.put(31, JavaChestMenus.display(
                    Material.CLOCK,
                    "受注済みの依頼",
                    List.of("依頼者からは取り消せません。")));
        }
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "自分の依頼・受注へ戻る",
                List.of(),
                () -> openMine(player, actionHandler, page)));
        menus.open(player, "クエスト #" + quest.id(), 45, entries);
    }

    private void openSubmitConfirmation(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = activeQuestOrReturn(player, questId, actionHandler, page);
        if (quest == null) {
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        entries.put(31, JavaChestMenus.terminalAction(
                Material.LIME_CONCRETE,
                "納品する",
                List.of("依頼品を回収し、報酬を受取箱へ入れます。"),
                () -> actionHandler.accept(action(QuestFormAction.Kind.SUBMIT, quest.id()))));
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openMineAction(player, quest.id(), actionHandler, page)));
        menus.open(player, "納品の最終確認", 45, entries);
    }

    private void openAbandonConfirmation(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = activeQuestOrReturn(player, questId, actionHandler, page);
        if (quest == null) {
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        entries.put(31, JavaChestMenus.terminalAction(
                Material.RED_CONCRETE,
                "辞退する",
                List.of("ほかの人が受注できる状態へ戻ります。"),
                () -> actionHandler.accept(action(QuestFormAction.Kind.ABANDON, quest.id()))));
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openMineAction(player, quest.id(), actionHandler, page)));
        menus.open(player, "クエスト辞退の確認", 45, entries);
    }

    private void openCancelConfirmation(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = repository.find(questId).orElse(null);
        if (quest == null
                || quest.status() != QuestListing.Status.OPEN
                || !quest.ownerId().equals(player.getUniqueId())) {
            player.sendMessage("その依頼は見つからないか、取り消せない状態です。");
            openMine(player, actionHandler, page);
            return;
        }
        Map<Integer, JavaChestMenus.MenuEntry> entries = questDetail(quest);
        entries.put(31, JavaChestMenus.terminalAction(
                Material.RED_CONCRETE,
                "依頼を取り消す",
                List.of("報酬は受取箱へ戻ります。"),
                () -> actionHandler.accept(action(QuestFormAction.Kind.CANCEL, quest.id()))));
        entries.put(36, JavaChestMenus.action(
                Material.ARROW,
                "戻る",
                List.of(),
                () -> openMineAction(player, quest.id(), actionHandler, page)));
        menus.open(player, "依頼取り消しの確認", 45, entries);
    }

    private QuestListing activeQuestOrReturn(
            Player player,
            long questId,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing quest = repository.find(questId).orElse(null);
        if (quest == null || quest.status() != QuestListing.Status.ACCEPTED) {
            player.sendMessage("そのクエストは見つからないか、すでに終了しています。");
            openMine(player, actionHandler, page);
            return null;
        }
        return quest;
    }

    private static Map<Integer, JavaChestMenus.MenuEntry> questDetail(QuestListing quest) {
        Map<Integer, JavaChestMenus.MenuEntry> entries = new HashMap<>();
        entries.put(11, JavaChestMenus.display(
                requestedIcon(quest),
                List.of("依頼品: " + quest.requestedLabel())));
        entries.put(13, JavaChestMenus.display(
                Material.ARROW,
                "納品すると報酬を受け取れます",
                List.of(
                        "依頼者: " + quest.ownerName(),
                        "受注後の期限: " + quest.fulfillmentHours() + "時間")));
        entries.put(15, JavaChestMenus.display(
                rewardIcon(quest.reward()),
                List.of("報酬: " + quest.rewardLabel())));
        return entries;
    }

    private static List<String> questLore(QuestListing quest, String action) {
        List<String> lore = new ArrayList<>();
        lore.add("依頼品: " + quest.requestedLabel());
        lore.add("報酬: " + quest.rewardLabel());
        lore.add("受注後の期限: " + quest.fulfillmentHours() + "時間");
        lore.add("依頼者: " + quest.ownerName());
        if (!action.isBlank()) {
            lore.add(action);
        }
        return lore;
    }

    private static List<String> mineLore(Player player, QuestListing quest) {
        List<String> lore = questLore(quest, "クリックして操作");
        if (quest.ownerId().equals(player.getUniqueId())) {
            lore.add(quest.status() == QuestListing.Status.OPEN
                    ? "状態: 依頼中・募集中"
                    : "状態: 依頼中・" + quest.workerName() + "さんが受注中");
        } else {
            lore.add("状態: 受注中・残り " + remaining(quest.acceptedDeadlineMillis()));
        }
        return lore;
    }

    private static ItemStack requestedIcon(QuestListing quest) {
        return requestedIcon(
                quest.requestedItemId(), quest.requestedItemName(), quest.requestedCount());
    }

    private static ItemStack requestedIcon(QuestDraft draft) {
        return requestedIcon(
                draft.requestedItemId(), draft.requestedItemName(), draft.requestedCount());
    }

    private static ItemStack requestedIcon(String itemId, String itemName, int count) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || material.isAir()) {
            return JavaChestMenus.icon(
                    Material.BARRIER, itemName + " x" + count, List.of("アイテム表示を取得できません。"));
        }
        ItemStack icon = new ItemStack(material, Math.min(count, material.getMaxStackSize()));
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(itemName + " x" + count)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack inputItemIcon(ItemStack item, int count) {
        ItemStack icon = item.clone();
        if (count > 0) {
            icon.setAmount(Math.min(count, icon.getMaxStackSize()));
        }
        String label = MarketItems.displayName(item) + (count > 0 ? " x" + count : "");
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(label)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack rewardIcon(ItemStack reward) {
        ItemStack icon = reward.clone();
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(MarketItems.displayName(icon))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        icon.setItemMeta(meta);
        return icon;
    }

    private static QuestDraft decodeDraft(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return QuestDraft.decode(encoded);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String remaining(long deadlineMillis) {
        long millis = Math.max(0, deadlineMillis - System.currentTimeMillis());
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return hours + "時間" + minutes + "分";
    }

    private static QuestFormAction action(QuestFormAction.Kind kind, long questId) {
        return new QuestFormAction(kind, questId, 0, 0);
    }
}
