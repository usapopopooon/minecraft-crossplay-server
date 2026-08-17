package net.usapo.eventbridge;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateExchangeFormGateway implements BedrockExchangeFormGateway {
    private final JavaPlugin plugin;
    private final FloodgateApi floodgate;

    FloodgateExchangeFormGateway(JavaPlugin plugin) {
        this.plugin = plugin;
        this.floodgate = FloodgateApi.getInstance();
    }

    @Override
    public boolean open(Player player, Consumer<ExchangeSelection> selectionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("Minecraft 交換所")
                .content("交換内容を選んでください。残高と処理結果は本人だけに表示されます。")
                .button("Minecraft XPへ交換")
                .button("資源へ交換")
                .button("手持ちエメラルドを交換")
                .button("XP残高を確認")
                .button("閉じる")
                .validResultHandler(response -> runOnMain(() -> {
                    switch (response.clickedButtonId()) {
                        case 0 -> openOptions(
                                player,
                                "Minecraft XP交換",
                                ExchangeCatalog.XP,
                                selectionHandler,
                                () -> open(player, selectionHandler));
                        case 1 -> openOptions(
                                player,
                                "資源交換",
                                ExchangeCatalog.RESOURCES,
                                selectionHandler,
                                () -> open(player, selectionHandler));
                        case 2 -> openOptions(
                                player,
                                "エメラルド交換",
                                ExchangeCatalog.EMERALD_DIAMOND,
                                selectionHandler,
                                () -> open(player, selectionHandler));
                        case 3 -> selectionHandler.accept(ExchangeSelection.balance());
                        default -> {
                            // 閉じるボタンでは何もしない。
                        }
                    }
                }))
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openOptions(
            Player player,
            String title,
            List<ExchangeSelection> options,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        if (!player.isOnline()) {
            return;
        }
        var builder = SimpleForm.builder()
                .title(title)
                .content("交換内容を選ぶと確認画面が表示されます。");
        options.forEach(option -> builder.button(option.description()));
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < options.size()) {
                        ExchangeSelection selection = options.get(selected);
                        openConfirmation(
                                player,
                                selection,
                                selectionHandler,
                                () -> openOptions(
                                        player,
                                        title,
                                        options,
                                        selectionHandler,
                                        backAction));
                    } else {
                        backAction.run();
                    }
                }))
                .build();
        sendFormOrFallback(player, form);
    }

    private void openConfirmation(
            Player player,
            ExchangeSelection selection,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        if (!player.isOnline()) {
            return;
        }
        ModalForm confirmation = ModalForm.builder()
                .title("交換内容の確認")
                .content(selection.description() + "\nこの内容で交換しますか？")
                .button1("交換する")
                .button2("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    if (response.clickedFirst()) {
                        if (player.isOnline()) {
                            selectionHandler.accept(selection);
                        }
                    } else {
                        backAction.run();
                    }
                }))
                .build();
        sendFormOrFallback(player, confirmation);
    }

    private void sendFormOrFallback(Player player, org.geysermc.cumulus.form.Form form) {
        if (!floodgate.sendForm(player.getUniqueId(), form)) {
            player.sendMessage("フォームを表示できませんでした。/exchange の引数付きコマンドをお試しください。");
        }
    }

    private void runOnMain(Runnable operation) {
        plugin.getServer().getScheduler().runTask(plugin, operation);
    }
}
