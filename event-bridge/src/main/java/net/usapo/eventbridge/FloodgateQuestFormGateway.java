package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateQuestFormGateway implements BedrockQuestFormGateway {
    private static final int PAGE_SIZE = 10;

    private final JavaPlugin plugin;
    private final QuestRepository repository;
    private final FloodgateApi floodgate;
    private final NamespacedKey draftKey;

    FloodgateQuestFormGateway(JavaPlugin plugin, QuestRepository repository) {
        this(
                plugin,
                repository,
                FloodgateApi.getInstance(),
                new NamespacedKey(plugin, "quest_draft"));
    }

    FloodgateQuestFormGateway(
            JavaPlugin plugin,
            QuestRepository repository,
            FloodgateApi floodgate,
            NamespacedKey draftKey) {
        this.plugin = plugin;
        this.repository = repository;
        this.floodgate = floodgate;
        this.draftKey = draftKey;
    }

    @Override
    public boolean open(Player player, Consumer<QuestFormAction> actionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        String encodedDraft = player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING);
        QuestDraft draft = decodeDraft(encodedDraft);
        int claims = repository.pendingClaims(player.getUniqueId()).size();
        String content = "通常アイテムやエンチャント本の納品を依頼・受注できます。"
                + "報酬と納品物は安全な受取箱へ入ります。"
                + "\n受取箱: " + claims + "件";
        if (draft != null) {
            content += "\n\n公開待ち: " + draft.requestedItemName() + " x"
                    + draft.requestedCount() + " / 期限 " + draft.fulfillmentHours() + "時間";
        } else if (encodedDraft != null) {
            content += "\n\n公開待ちの下書きが壊れています。破棄して作り直してください。";
        }
        var builder = SimpleForm.builder()
                .title("ギルド・クエスト掲示板")
                .content(content);
        List<Runnable> handlers = new ArrayList<>();
        builder.button("募集中の依頼を見る");
        handlers.add(() -> openListings(player, actionHandler, 1));
        builder.button("依頼を作る");
        handlers.add(() -> openCreate(player, actionHandler));
        if (draft != null) {
            builder.button("公開内容を確認");
            handlers.add(() -> openPublicationConfirmation(player, actionHandler));
        }
        if (encodedDraft != null) {
            builder.button("下書きを破棄");
            handlers.add(() -> openDiscardConfirmation(player, actionHandler));
        }
        builder.button("自分の依頼・受注");
        handlers.add(() -> openMine(player, actionHandler, 1));
        if (claims > 0) {
            builder.button("受取箱を受け取る（" + claims + "件）");
            handlers.add(() -> actionHandler.accept(action(QuestFormAction.Kind.CLAIM, 0)));
        }
        builder.button("閉じる");
        handlers.add(() -> {});
        SimpleForm form = builder
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < handlers.size()) {
                        handlers.get(selected).run();
                    }
                }))
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openListings(
            Player player, Consumer<QuestFormAction> actionHandler, int requestedPage) {
        List<QuestListing> allQuests = repository.openQuests();
        if (allQuests.isEmpty()) {
            openInfo(
                    player,
                    "募集中のクエスト",
                    "現在募集中のクエストはありません。",
                    () -> open(player, actionHandler));
            return;
        }
        BedrockFormPages.Page<QuestListing> page =
                BedrockFormPages.select(allQuests, requestedPage, PAGE_SIZE);
        var builder = SimpleForm.builder()
                .title("募集中のクエスト")
                .content("受注する依頼を選んでください。 " + page.number() + " / " + page.total());
        List<Runnable> handlers = new ArrayList<>();
        page.items().forEach(quest -> {
            boolean ownQuest = quest.ownerId().equals(player.getUniqueId());
            builder.button((ownQuest ? "【自分の依頼】 " : "") + label(quest));
            handlers.add(ownQuest
                    ? () -> openOwnQuestFromBrowse(
                            player, quest, actionHandler, page.number())
                    : () -> openAccept(player, quest, actionHandler, page.number()));
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

    private void openAccept(
            Player player,
            QuestListing quest,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        ModalForm form = ModalForm.builder()
                .title("クエスト受注の確認")
                .content(label(quest) + "\n依頼者: " + quest.ownerName() + "\n\n受注後 "
                        + quest.fulfillmentHours() + "時間以内に一括納品しますか？")
                .button1("受注する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(action(QuestFormAction.Kind.ACCEPT, quest.id()));
                    } else {
                        openListings(player, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openCreate(Player player, Consumer<QuestFormAction> actionHandler) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSupportedRequest(held)) {
            openInfo(
                    player,
                    "納品依頼を作る",
                    QuestItems.requestRejectionMessage(held),
                    () -> open(player, actionHandler));
            return;
        }
        ItemStack shownItem = held.clone();
        CustomForm form = creationForm(
                MarketItems.questDisplayName(held),
                held.getMaxStackSize(),
                action -> runOnMain(() -> {
                    if (!shownItem.equals(player.getInventory().getItemInMainHand())) {
                        openInfo(
                                player,
                                "納品依頼を作る",
                                "入力中に依頼品が変わりました。もう一度依頼作成を開いてください。",
                                () -> open(player, actionHandler));
                        return;
                    }
                    actionHandler.accept(action);
                }));
        sendForm(player, form);
    }

    static CustomForm creationForm(
            String itemName,
            int maximumCount,
            Consumer<QuestFormAction> actionHandler) {
        if (maximumCount < 1) {
            throw new IllegalArgumentException("maximumCount must be positive");
        }
        int defaultCount = Math.min(32, maximumCount);
        var builder = CustomForm.builder().title("納品依頼を作る");
        if (maximumCount == 1) {
            return builder
                    .label(itemName + " x1（エンチャント本は1冊固定）")
                    .slider("受注後の納品期限（時間）", 1, 72, 1, 24)
                    .validResultHandler(response -> actionHandler.accept(new QuestFormAction(
                            QuestFormAction.Kind.CREATE,
                            0,
                            1,
                            Math.round(response.asSlider(1)))))
                    .build();
        }
        return builder
                .slider(itemName + " の依頼数（1スタック以内）", 1, maximumCount, 1, defaultCount)
                .slider("受注後の納品期限（時間）", 1, 72, 1, 24)
                .validResultHandler(response -> actionHandler.accept(new QuestFormAction(
                        QuestFormAction.Kind.CREATE,
                        0,
                        Math.round(response.asSlider(0)),
                        Math.round(response.asSlider(1)))))
                .build();
    }

    private void openPublicationConfirmation(
            Player player, Consumer<QuestFormAction> actionHandler) {
        String encoded = player.getPersistentDataContainer().get(draftKey, PersistentDataType.STRING);
        QuestDraft draft = decodeDraft(encoded);
        if (draft == null) {
            openInfo(
                    player,
                    "公開内容の確認",
                    "公開できる下書きがありません。依頼を作り直してください。",
                    () -> open(player, actionHandler));
            return;
        }
        ItemStack reward = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSupportedReward(reward)) {
            openInfo(
                    player,
                    "公開内容の確認",
                    QuestItems.rewardRejectionMessage(reward),
                    () -> open(player, actionHandler));
            return;
        }
        ModalForm form = ModalForm.builder()
                .title("公開内容の最終確認")
                .content(publicationConfirmation(draft, reward))
                .button1("報酬を預けて公開")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(action(QuestFormAction.Kind.CONFIRM, 0));
                    } else {
                        open(player, actionHandler);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    static String publicationConfirmation(QuestDraft draft, ItemStack reward) {
        return "依頼品: " + draft.requestedItemName() + " x" + draft.requestedCount()
                + "\n受注後の期限: " + draft.fulfillmentHours() + "時間"
                + "\n報酬: " + MarketItems.questDisplayName(reward) + " x" + reward.getAmount()
                + "\n\nこの報酬スタックを預けて公開しますか？";
    }

    private void openMine(
            Player player, Consumer<QuestFormAction> actionHandler, int requestedPage) {
        List<QuestListing> allQuests = repository.activeFor(player.getUniqueId());
        if (allQuests.isEmpty()) {
            openInfo(
                    player,
                    "自分の依頼・受注",
                    "進行中の依頼・受注はありません。受取箱はメイン画面から確認できます。",
                    () -> open(player, actionHandler));
            return;
        }
        BedrockFormPages.Page<QuestListing> page =
                BedrockFormPages.select(allQuests, requestedPage, PAGE_SIZE);
        var builder = SimpleForm.builder()
                .title("自分の依頼・受注")
                .content("詳細または操作するクエストを選んでください。 "
                        + page.number() + " / " + page.total());
        List<Runnable> handlers = new ArrayList<>();
        page.items().forEach(quest -> {
            builder.button(roleLabel(player, quest) + "\n" + label(quest));
            handlers.add(() -> openMineAction(player, quest, actionHandler, page.number()));
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

    private void openMineAction(
            Player player,
            QuestListing quest,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        boolean owner = quest.ownerId().equals(player.getUniqueId());
        if (owner && quest.status() == QuestListing.Status.OPEN) {
            ModalForm form = ModalForm.builder()
                    .title("依頼を取り消す")
                    .content(label(quest) + "\n\n取り消すと報酬は受取箱へ戻ります。")
                    .button1("取り消す")
                    .button2("戻る")
                    .validResultHandler(response -> runOnMain(() -> {
                        if (response.clickedFirst()) {
                            actionHandler.accept(action(
                                    QuestFormAction.Kind.CANCEL, quest.id()));
                        } else {
                            openMine(player, actionHandler, page);
                        }
                    }))
                    .build();
            sendForm(player, form);
            return;
        }
        if (!owner && quest.status() == QuestListing.Status.ACCEPTED) {
            SimpleForm form = SimpleForm.builder()
                    .title("受注中のクエスト")
                    .content(label(quest)
                            + "\n納品では依頼品をメインハンドに必要数以上まとめて持ってください。")
                    .button("納品する")
                    .button("辞退して再募集する")
                    .button("戻る")
                    .validResultHandler(response -> runOnMain(() -> {
                        switch (response.clickedButtonId()) {
                            case 0 -> openSubmit(player, quest, actionHandler, page);
                            case 1 -> openAbandon(player, quest, actionHandler, page);
                            default -> openMine(player, actionHandler, page);
                        }
                    }))
                    .build();
            sendForm(player, form);
            return;
        }
        SimpleForm form = SimpleForm.builder()
                .title("受注済みの依頼")
                .content(label(quest) + "\n受注済みの依頼は依頼者から取り消せません。")
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> openMine(player, actionHandler, page)))
                .build();
        sendForm(player, form);
    }

    private void openSubmit(
            Player player,
            QuestListing quest,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        ModalForm form = ModalForm.builder()
                .title("納品の最終確認")
                .content("メインハンドから " + quest.requestedLabel()
                        + " を納品し、報酬 " + quest.rewardLabel() + " を受取箱へ入れますか？")
                .button1("納品する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(action(QuestFormAction.Kind.SUBMIT, quest.id()));
                    } else {
                        openMineAction(player, quest, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openAbandon(
            Player player,
            QuestListing quest,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        ModalForm form = ModalForm.builder()
                .title("クエストを辞退する")
                .content("#" + quest.id() + " を辞退すると、ほかの人が受注できる状態へ戻ります。")
                .button1("辞退する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(action(QuestFormAction.Kind.ABANDON, quest.id()));
                    } else {
                        openMineAction(player, quest, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openDiscardConfirmation(
            Player player, Consumer<QuestFormAction> actionHandler) {
        QuestDraft draft = decodeDraft(player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING));
        String content = draft == null
                ? "公開待ちの下書きを破棄します。アイテムは消費しません。"
                : "依頼品: " + draft.requestedItemName() + " x" + draft.requestedCount()
                        + "\n期限: " + draft.fulfillmentHours() + "時間"
                        + "\n\nこの下書きを破棄しますか？アイテムは消費しません。";
        ModalForm form = ModalForm.builder()
                .title("下書き破棄の確認")
                .content(content)
                .button1("下書きを破棄する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        actionHandler.accept(action(QuestFormAction.Kind.DISCARD, 0));
                    } else {
                        open(player, actionHandler);
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openOwnQuestFromBrowse(
            Player player,
            QuestListing quest,
            Consumer<QuestFormAction> actionHandler,
            int page) {
        QuestListing current = repository.find(quest.id()).orElse(null);
        if (current == null
                || current.status() != QuestListing.Status.OPEN
                || !current.ownerId().equals(player.getUniqueId())) {
            openInfo(
                    player,
                    "自分の依頼",
                    "その依頼は見つからないか、すでに受注されています。",
                    () -> openListings(player, actionHandler, page));
            return;
        }
        SimpleForm form = SimpleForm.builder()
                .title("自分の依頼")
                .content(label(current) + "\n\n自分の依頼は受注できません。")
                .button("取り消し内容を確認")
                .button("募集一覧へ戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedButtonId() == 0) {
                        openMineAction(player, current, actionHandler, page);
                    } else {
                        openListings(player, actionHandler, page);
                    }
                }))
                .build();
        sendForm(player, form);
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

    private static QuestFormAction action(QuestFormAction.Kind kind, long questId) {
        return new QuestFormAction(kind, questId, 0, 0);
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

    private static String label(QuestListing quest) {
        return "#" + quest.id() + " " + quest.requestedLabel() + " → " + quest.rewardLabel();
    }

    private static String roleLabel(Player player, QuestListing quest) {
        if (quest.ownerId().equals(player.getUniqueId())) {
            return quest.status() == QuestListing.Status.OPEN ? "依頼中・募集中" : "依頼中・受注済み";
        }
        return "受注中";
    }

    private void sendForm(Player player, org.geysermc.cumulus.form.Form form) {
        if (!player.isOnline()) {
            return;
        }
        if (!floodgate.sendForm(player.getUniqueId(), form)) {
            sendMessage(player, "フォームを表示できませんでした。/quest コマンドをお試しください。");
        }
    }

    private static void sendMessage(Player player, String message) {
        if (player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private void runOnMain(Runnable operation) {
        plugin.getServer().getScheduler().runTask(plugin, operation);
    }
}
