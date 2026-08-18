package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateGachaFormGateway implements BedrockGachaFormGateway {
    private final JavaPlugin plugin;
    private final FloodgateApi floodgate;

    FloodgateGachaFormGateway(JavaPlugin plugin) {
        this.plugin = plugin;
        this.floodgate = FloodgateApi.getInstance();
    }

    @Override
    public boolean open(Player player, Consumer<ItemGachaSelection> selectionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("Minecraft アイテムガチャ")
                .content("欲しい景品の種類を選んでください。\n"
                        + "どの種類でも、日本時間0:00更新・合計1日3回までです。")
                .button("おまかせ")
                .button("資源・採掘")
                .button("冒険")
                .button("装備・強化")
                .button("閉じる")
                .validResultHandler(response -> {
                    ItemGachaCategory category = switch (response.clickedButtonId()) {
                        case 0 -> ItemGachaCategory.ALL;
                        case 1 -> ItemGachaCategory.RESOURCES;
                        case 2 -> ItemGachaCategory.ADVENTURE;
                        case 3 -> ItemGachaCategory.EQUIPMENT;
                        default -> null;
                    };
                    if (category != null) {
                        runOnMain(() -> openKindSelection(player, category, selectionHandler));
                    }
                })
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openKindSelection(
            Player player,
            ItemGachaCategory category,
            Consumer<ItemGachaSelection> selectionHandler) {
        if (!player.isOnline()) {
            return;
        }
        SimpleForm form = SimpleForm.builder()
                .title(category.label() + "ガチャ")
                .content("引き方を選んでください。ランク確率と1日の回数上限は共通です。")
                .button("通常ガチャ\n100 XP")
                .button("R以上確定ガチャ\n1,000 XP")
                .button("戻る")
                .validResultHandler(response -> {
                    ItemGachaKind kind = switch (response.clickedButtonId()) {
                        case 0 -> ItemGachaKind.NORMAL;
                        case 1 -> ItemGachaKind.PREMIUM;
                        default -> null;
                    };
                    if (kind != null) {
                        runOnMain(() -> openConfirmation(
                                player, category, kind, selectionHandler));
                    } else {
                        runOnMain(() -> open(player, selectionHandler));
                    }
                })
                .build();
        if (!floodgate.sendForm(player.getUniqueId(), form)) {
            player.sendMessage("フォームを表示できませんでした。コマンド入力をお試しください。");
        }
    }

    private void openConfirmation(
            Player player,
            ItemGachaCategory category,
            ItemGachaKind kind,
            Consumer<ItemGachaSelection> selectionHandler) {
        if (!player.isOnline()) {
            return;
        }
        ModalForm confirmation = ModalForm.builder()
                .title(category.label() + "・" + kind.label() + "ガチャの確認")
                .content(kind.costXp() + " XPを使ってガチャを引きます。\n"
                        + "通常とR以上確定を合わせて1日3回までです。")
                .button1(kind.costXp() + " XPで引く")
                .button2("戻る")
                .validResultHandler(response -> {
                    if (response.clickedFirst()) {
                        runOnMain(() -> selectionHandler.accept(
                                new ItemGachaSelection(category, kind)));
                    } else {
                        runOnMain(() -> openKindSelection(player, category, selectionHandler));
                    }
                })
                .build();
        if (!floodgate.sendForm(player.getUniqueId(), confirmation)) {
            String kindArgument = kind == ItemGachaKind.NORMAL ? "normal" : "rare";
            String categoryArgument = category == ItemGachaCategory.ALL
                    ? ""
                    : category.commandName() + " ";
            player.sendMessage("フォームを表示できませんでした。/gacha "
                    + categoryArgument + kindArgument + " をお試しください。");
        }
    }

    private void runOnMain(Runnable operation) {
        plugin.getServer().getScheduler().runTask(plugin, operation);
    }
}
