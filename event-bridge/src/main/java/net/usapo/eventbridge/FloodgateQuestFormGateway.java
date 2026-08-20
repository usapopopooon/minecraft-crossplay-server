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
        this.plugin = plugin;
        this.repository = repository;
        this.floodgate = FloodgateApi.getInstance();
        this.draftKey = new NamespacedKey(plugin, "quest_draft");
    }

    @Override
    public boolean open(Player player, Consumer<QuestFormAction> actionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        String encodedDraft = player.getPersistentDataContainer()
                .get(draftKey, PersistentDataType.STRING);
        QuestDraft draft = decodeDraft(encodedDraft);
        String content = "アイテム納品を依頼・受注できます。報酬と納品物は安全な受取箱へ入ります。";
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
            handlers.add(() -> actionHandler.accept(action(QuestFormAction.Kind.DISCARD, 0)));
        }
        builder.button("自分の依頼・受注");
        handlers.add(() -> openMine(player, actionHandler, 1));
        builder.button("受取箱を受け取る");
        handlers.add(() -> actionHandler.accept(action(QuestFormAction.Kind.CLAIM, 0)));
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
            sendMessage(player, "現在募集中のクエストはありません。");
            return;
        }
        int pages = Math.max(1, (allQuests.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        List<QuestListing> quests = allQuests.stream()
                .skip((long) (page - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .toList();
        var builder = SimpleForm.builder()
                .title("募集中のクエスト")
                .content("受注する依頼を選んでください。 " + page + " / " + pages);
        List<Runnable> handlers = new ArrayList<>();
        quests.forEach(quest -> {
            builder.button(label(quest));
            handlers.add(() -> openAccept(player, quest, actionHandler, page));
        });
        if (page > 1) {
            builder.button("前のページ");
            handlers.add(() -> openListings(player, actionHandler, page - 1));
        }
        if (page < pages) {
            builder.button("次のページ");
            handlers.add(() -> openListings(player, actionHandler, page + 1));
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
        CustomForm form = CustomForm.builder()
                .title("納品依頼を作る")
                .input("依頼品を手に持ち、個数を入力（1スタック以内）", "例: 32")
                .input("受注後の納品期限（1〜72時間）", "例: 24", "24")
                .validResultHandler(response -> runOnMain(() -> {
                    try {
                        int count = Integer.parseInt(response.asInput(0).trim());
                        int hours = Integer.parseInt(response.asInput(1).trim());
                        if (count <= 0 || hours < 1 || hours > 72) {
                            throw new NumberFormatException("out of range");
                        }
                        actionHandler.accept(new QuestFormAction(
                                QuestFormAction.Kind.CREATE, 0, count, hours));
                    } catch (NumberFormatException error) {
                        sendMessage(player, "個数は1以上、期限は1〜72の整数で入力してください。");
                    }
                }))
                .build();
        sendForm(player, form);
    }

    private void openPublicationConfirmation(
            Player player, Consumer<QuestFormAction> actionHandler) {
        String encoded = player.getPersistentDataContainer().get(draftKey, PersistentDataType.STRING);
        QuestDraft draft = decodeDraft(encoded);
        if (draft == null) {
            sendMessage(player, "公開できる下書きがありません。依頼を作り直してください。");
            return;
        }
        ItemStack reward = player.getInventory().getItemInMainHand();
        if (!QuestItems.isSimpleStack(reward)) {
            sendMessage(player, "報酬にする通常アイテムのスタックをメインハンドへ持ってください。");
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
                + "\n報酬: " + MarketItems.displayName(reward) + " x" + reward.getAmount()
                + "\n\nこの報酬スタックを預けて公開しますか？";
    }

    private void openMine(
            Player player, Consumer<QuestFormAction> actionHandler, int requestedPage) {
        List<QuestListing> allQuests = repository.activeFor(player.getUniqueId());
        if (allQuests.isEmpty()) {
            sendMessage(player, "進行中の依頼・受注はありません。受取箱はメイン画面から確認できます。");
            return;
        }
        int pages = Math.max(1, (allQuests.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        List<QuestListing> quests = allQuests.stream()
                .skip((long) (page - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .toList();
        var builder = SimpleForm.builder()
                .title("自分の依頼・受注")
                .content("詳細または操作するクエストを選んでください。 " + page + " / " + pages);
        List<Runnable> handlers = new ArrayList<>();
        quests.forEach(quest -> {
            builder.button(roleLabel(player, quest) + "\n" + label(quest));
            handlers.add(() -> openMineAction(player, quest, actionHandler, page));
        });
        if (page > 1) {
            builder.button("前のページ");
            handlers.add(() -> openMine(player, actionHandler, page - 1));
        }
        if (page < pages) {
            builder.button("次のページ");
            handlers.add(() -> openMine(player, actionHandler, page + 1));
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
