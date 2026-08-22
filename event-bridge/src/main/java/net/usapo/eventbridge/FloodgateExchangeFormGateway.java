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
    private final ExchangeCatalog catalog;

    FloodgateExchangeFormGateway(JavaPlugin plugin) {
        this(plugin, FloodgateApi.getInstance(), new ExchangeCatalog());
    }

    FloodgateExchangeFormGateway(JavaPlugin plugin, ExchangeCatalog catalog) {
        this(plugin, FloodgateApi.getInstance(), catalog);
    }

    FloodgateExchangeFormGateway(JavaPlugin plugin, FloodgateApi floodgate) {
        this(plugin, floodgate, new ExchangeCatalog());
    }

    FloodgateExchangeFormGateway(
            JavaPlugin plugin, FloodgateApi floodgate, ExchangeCatalog catalog) {
        this.plugin = plugin;
        this.floodgate = floodgate;
        this.catalog = catalog;
    }

    @Override
    public boolean open(Player player, Consumer<ExchangeSelection> selectionHandler) {
        if (!floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        SimpleForm form = SimpleForm.builder()
                .title("資源交換所")
                .content("交換内容を選んでください。残高と処理結果は本人だけに表示されます。")
                .button("Minecraft XPへ交換")
                .button("XPで資源を購入")
                .button("ダイヤ・エメラルドを両替")
                .button("資源をXPで売却")
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
                        case 1 -> openResourceGroups(player, selectionHandler);
                        case 2 -> openOptions(
                                player,
                                "ダイヤ・エメラルド両替",
                                ExchangeCatalog.VALUABLE_CONVERSIONS,
                                selectionHandler,
                                () -> open(player, selectionHandler));
                        case 3 -> openBuybackCategories(player, selectionHandler);
                        case 4 -> selectionHandler.accept(ExchangeSelection.balance());
                        default -> {
                            // 閉じるボタンでは何もしない。
                        }
                    }
                }))
                .build();
        return floodgate.sendForm(player.getUniqueId(), form);
    }

    private void openResourceGroups(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        if (!player.isOnline()) {
            return;
        }
        var builder = SimpleForm.builder()
                .title("XPで資源を購入")
                .content("ほしい資源を選んでください。次に個数と必要サーバーXPを選べます。");
        List<ExchangeCatalog.ResourceGroup> groups = catalog.resourceGroups();
        groups.forEach(group -> builder.button(
                group.itemName() + "\n" + group.amountsLabel()));
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < groups.size()) {
                        ExchangeCatalog.ResourceGroup group = groups.get(selected);
                        openOptions(
                                player,
                                group.itemName() + "へ交換",
                                group.options(),
                                selectionHandler,
                                () -> openResourceGroups(player, selectionHandler));
                    } else {
                        open(player, selectionHandler);
                    }
                }))
                .build();
        sendFormOrFallback(player, form);
    }

    private void openBuybackCategories(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        MaterialBuybackCatalog.Rate emerald = MaterialBuybackCatalog.EMERALD_RATE;
        int emeraldCount = MaterialBuybackCatalog.plainCount(player, emerald.material());
        SimpleForm form = SimpleForm.builder()
                .title("資源をXPで売却")
                .content("売却する種類を選んでください。"
                        + "\n1日の売却上限: 3,000 サーバーXP（毎日0時・日本時間に更新）"
                        + "\n本日の残り枠は処理時に確認します。")
                .button("エメラルド\n所持" + emeraldCount + "個 / 64個 → 500 XP")
                .button("資材\n土・砂・砂岩・深層岩など")
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    switch (response.clickedButtonId()) {
                        case 0 -> {
                            if (emeraldCount >= MaterialBuybackCatalog.STACK_SIZE) {
                                openBuybackAmounts(
                                        player,
                                        emerald,
                                        selectionHandler,
                                        () -> openBuybackCategories(player, selectionHandler));
                            } else {
                                openUnavailableEmeraldBuyback(player, selectionHandler);
                            }
                        }
                        case 1 -> openBuybackMaterials(player, selectionHandler);
                        default -> open(player, selectionHandler);
                    }
                }))
                .build();
        sendFormOrFallback(player, form);
    }

    private void openUnavailableEmeraldBuyback(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        SimpleForm form = SimpleForm.builder()
                .title("エメラルド")
                .content("通常のエメラルドを64個以上インベントリへ入れてください。"
                        + "\n64個 → 500サーバーXP")
                .button("戻る")
                .validResultHandler(response -> runOnMain(
                        () -> openBuybackCategories(player, selectionHandler)))
                .build();
        sendFormOrFallback(player, form);
    }

    private void openBuybackMaterials(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        List<MaterialBuybackCatalog.Rate> available = MaterialBuybackCatalog.MATERIAL_RATES.stream()
                .filter(rate -> MaterialBuybackCatalog.plainCount(player, rate.material())
                        >= MaterialBuybackCatalog.STACK_SIZE)
                .toList();
        if (available.isEmpty()) {
            SimpleForm form = SimpleForm.builder()
                    .title("資材")
                    .content("交換できる通常資材がありません。対象資材を64個以上"
                            + "インベントリへ入れてください。\n"
                            + "対象: 土・砂・砂岩・深層岩・深層岩の丸石・凝灰岩")
                    .button("戻る")
                    .validResultHandler(response -> runOnMain(
                            () -> openBuybackCategories(player, selectionHandler)))
                    .build();
            sendFormOrFallback(player, form);
            return;
        }
        var builder = SimpleForm.builder()
                .title("資材")
                .content("交換する資材を選んでください。通常アイテムだけを64個単位で回収します。"
                        + "\n1日の売却上限: 3,000 サーバーXP（毎日0時・日本時間に更新）"
                        + "\n本日の残り枠は処理時に確認し、超過時は回収しません。");
        available.forEach(rate -> {
            int count = MaterialBuybackCatalog.plainCount(player, rate.material());
            int exchangeable = count / MaterialBuybackCatalog.STACK_SIZE
                    * MaterialBuybackCatalog.STACK_SIZE;
            builder.button(rate.itemName() + ": 通常品" + count + "個（交換可能"
                    + exchangeable + "個）\n64個 → "
                    + rate.rewardXpPerStack() + " サーバーXP");
        });
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < available.size()) {
                        openBuybackAmounts(
                                player,
                                available.get(selected),
                                selectionHandler,
                                () -> openBuybackMaterials(player, selectionHandler));
                    } else {
                        openBuybackCategories(player, selectionHandler);
                    }
                }))
                .build();
        sendFormOrFallback(player, form);
    }

    private void openBuybackAmounts(
            Player player,
            MaterialBuybackCatalog.Rate rate,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        int available = MaterialBuybackCatalog.plainCount(player, rate.material());
        int all = available / MaterialBuybackCatalog.STACK_SIZE
                * MaterialBuybackCatalog.STACK_SIZE;
        if (all < MaterialBuybackCatalog.STACK_SIZE) {
            backAction.run();
            return;
        }
        List<MaterialBuybackCatalog.QuantityOption> options =
                MaterialBuybackCatalog.quantityOptions(rate, all);
        var builder = SimpleForm.builder()
                .title(rate.itemName() + "の売却数")
                .content("数量を選ぶと、回収数と獲得サーバーXPの確認画面が表示されます。");
        options.forEach(option -> {
            ExchangeSelection selection = MaterialBuybackCatalog.selection(
                    rate, option.itemCount());
            builder.button(option.label() + "\n" + selection.description());
        });
        SimpleForm form = builder
                .button("戻る")
                .validResultHandler(response -> runOnMain(() -> {
                    int selected = response.clickedButtonId();
                    if (selected >= 0 && selected < options.size()) {
                        ExchangeSelection selection = MaterialBuybackCatalog.selection(
                                rate, options.get(selected).itemCount());
                        openConfirmation(
                                player,
                                selection,
                                selectionHandler,
                                () -> openBuybackAmounts(
                                        player, rate, selectionHandler, backAction));
                    } else {
                        backAction.run();
                    }
                }))
                .build();
        sendFormOrFallback(player, form);
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
                .content(selection.description()
                        + (selection.kind() == ExchangeKind.MATERIAL_BUYBACK
                                ? "\n名前や特殊データのない通常アイテムだけを回収します。"
                                : "")
                        + "\nこの内容で交換しますか？")
                .button1(selection.kind() == ExchangeKind.MATERIAL_BUYBACK
                        ? "売却する"
                        : "交換する")
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
