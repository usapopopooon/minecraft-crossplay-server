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
    public boolean open(Player player, Consumer<ItemGachaKind> selectionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("Minecraft アイテムガチャ")
                .content("通常とR以上確定を合わせて、日本時間0:00更新・1日3回までです。\n"
                        + "景品は抽選まで秘密です。")
                .button("通常ガチャ\n100 XP")
                .button("R以上確定ガチャ\n1,000 XP")
                .button("閉じる")
                .validResultHandler(response -> {
                    ItemGachaKind kind = switch (response.clickedButtonId()) {
                        case 0 -> ItemGachaKind.NORMAL;
                        case 1 -> ItemGachaKind.PREMIUM;
                        default -> null;
                    };
                    if (kind != null) {
                        runOnMain(() -> openConfirmation(player, kind, selectionHandler));
                    }
                })
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openConfirmation(
            Player player,
            ItemGachaKind kind,
            Consumer<ItemGachaKind> selectionHandler) {
        if (!player.isOnline()) {
            return;
        }
        ModalForm confirmation = ModalForm.builder()
                .title(kind.label() + "ガチャの確認")
                .content(kind.costXp() + " XPを使ってガチャを引きます。\n"
                        + "通常とR以上確定を合わせて1日3回までです。")
                .button1(kind.costXp() + " XPで引く")
                .button2("戻る")
                .validResultHandler(response -> {
                    if (response.clickedFirst()) {
                        runOnMain(() -> selectionHandler.accept(kind));
                    } else {
                        runOnMain(() -> open(player, selectionHandler));
                    }
                })
                .build();
        if (!floodgate.sendForm(player.getUniqueId(), confirmation)) {
            player.sendMessage("フォームを表示できませんでした。/gacha "
                    + (kind == ItemGachaKind.NORMAL ? "normal" : "rare")
                    + " をお試しください。");
        }
    }

    private void runOnMain(Runnable operation) {
        plugin.getServer().getScheduler().runTask(plugin, operation);
    }
}
