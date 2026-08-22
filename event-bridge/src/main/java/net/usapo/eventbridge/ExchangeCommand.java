package net.usapo.eventbridge;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class ExchangeCommand implements CommandExecutor, TabCompleter {
    private static final long REQUEST_COOLDOWN_MILLIS = 2_000;

    private final ExchangeRequestSink requestSink;
    private final BedrockExchangeFormGateway forms;
    private final JavaExchangeMenuGateway menus;
    private final ExchangeCatalog catalog;
    private final MaterialBuybackPendingRegistry pendingBuybacks;
    private final LongSupplier nowMillis;
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();

    ExchangeCommand(ExchangeRequestSink requestSink, BedrockExchangeFormGateway forms) {
        this(
                requestSink,
                forms,
                (player, handler) -> false,
                new ExchangeCatalog(),
                new MaterialBuybackPendingRegistry(),
                Clock.systemUTC()::millis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            JavaExchangeMenuGateway menus) {
        this(
                requestSink,
                forms,
                menus,
                new ExchangeCatalog(),
                new MaterialBuybackPendingRegistry(),
                Clock.systemUTC()::millis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            JavaExchangeMenuGateway menus,
            MaterialBuybackPendingRegistry pendingBuybacks) {
        this(
                requestSink,
                forms,
                menus,
                new ExchangeCatalog(),
                pendingBuybacks,
                Clock.systemUTC()::millis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            LongSupplier nowMillis) {
        this(
                requestSink,
                forms,
                (player, handler) -> false,
                new ExchangeCatalog(),
                new MaterialBuybackPendingRegistry(),
                nowMillis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            JavaExchangeMenuGateway menus,
            LongSupplier nowMillis) {
        this(
                requestSink,
                forms,
                menus,
                new ExchangeCatalog(),
                new MaterialBuybackPendingRegistry(),
                nowMillis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            JavaExchangeMenuGateway menus,
            MaterialBuybackPendingRegistry pendingBuybacks,
            LongSupplier nowMillis) {
        this(
                requestSink,
                forms,
                menus,
                new ExchangeCatalog(),
                pendingBuybacks,
                nowMillis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            JavaExchangeMenuGateway menus,
            ExchangeCatalog catalog,
            MaterialBuybackPendingRegistry pendingBuybacks,
            LongSupplier nowMillis) {
        this.requestSink = requestSink;
        this.forms = forms;
        this.menus = menus;
        this.catalog = catalog;
        this.pendingBuybacks = pendingBuybacks;
        this.nowMillis = nowMillis;
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
        if (arguments.length == 0) {
            if (!forms.open(player, selection -> submit(player, selection))
                    && !menus.open(player, selection -> submit(player, selection))) {
                sendUsage(player);
            }
            return true;
        }

        if (arguments[0].equalsIgnoreCase("buyback")) {
            submitBuyback(player, arguments);
            return true;
        }

        parse(arguments).ifPresentOrElse(
                selection -> submit(player, selection),
                () -> sendUsage(player));
        return true;
    }

    private void submitBuyback(Player player, String[] arguments) {
        if (arguments.length != 2) {
            sendUsage(player);
            return;
        }
        MaterialBuybackCatalog.Rate rate = MaterialBuybackCatalog
                .find(player.getInventory().getItemInMainHand().getType())
                .orElse(null);
        if (rate == null) {
            player.sendMessage("売却対象の通常アイテムをメインハンドに持ってください。");
            return;
        }
        int available = MaterialBuybackCatalog.plainCount(player, rate.material());
        String amount = arguments[1].toLowerCase(Locale.ROOT);
        int count = switch (amount) {
            case "1", "2", "4", "8", "16" ->
                    Integer.parseInt(amount) * MaterialBuybackCatalog.STACK_SIZE;
            case "max" -> Math.min(
                    available / MaterialBuybackCatalog.STACK_SIZE
                            * MaterialBuybackCatalog.STACK_SIZE,
                    MaterialBuybackCatalog.maximumDailyItemCount(rate));
            case "all" -> available / MaterialBuybackCatalog.STACK_SIZE
                    * MaterialBuybackCatalog.STACK_SIZE;
            default -> 0;
        };
        if (count > MaterialBuybackCatalog.maximumDailyItemCount(rate)) {
            player.sendMessage("この数量は、未使用でも1日の売却上限を超えます。"
                    + "/exchange buyback max で1回に選べる最大数を指定できます。");
            return;
        }
        if (count < MaterialBuybackCatalog.STACK_SIZE || count > available) {
            player.sendMessage("交換できる通常の" + rate.itemName() + "が不足しています。"
                    + "64個単位でインベントリに入れてください。");
            return;
        }
        submit(player, MaterialBuybackCatalog.selection(rate, count));
    }

    private Optional<ExchangeSelection> parse(String[] arguments) {
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (operation.equals("balance") && arguments.length == 1) {
            return Optional.of(ExchangeSelection.balance());
        }
        if (operation.equals("xp") && arguments.length == 2) {
            return parsePositiveInteger(arguments[1]).flatMap(ExchangeCatalog::findXp);
        }
        if (operation.equals("resource") && arguments.length == 3) {
            return parsePositiveInteger(arguments[2])
                    .flatMap(count -> catalog.findResource(arguments[1], count));
        }
        if (operation.equals("emerald-diamond") && arguments.length == 2) {
            return parsePositiveInteger(arguments[1]).flatMap(ExchangeCatalog::findEmeraldDiamond);
        }
        if (operation.equals("diamond-emerald") && arguments.length == 2) {
            return parsePositiveInteger(arguments[1]).flatMap(ExchangeCatalog::findDiamondEmerald);
        }
        return Optional.empty();
    }

    private static Optional<Integer> parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private void submit(Player player, ExchangeSelection selection) {
        if (!player.isOnline()) {
            return;
        }
        if (selection.kind() == ExchangeKind.MATERIAL_BUYBACK
                && pendingBuybacks.pendingRequest(player.getUniqueId()).isPresent()) {
            player.sendMessage("前の資源売却を処理中です。結果が表示されるまでお待ちください。"
                    + "長時間続く場合は運営へご連絡ください。");
            return;
        }
        long now = nowMillis.getAsLong();
        Long previous = lastRequests.put(player.getUniqueId(), now);
        if (previous != null && now - previous < REQUEST_COOLDOWN_MILLIS) {
            lastRequests.put(player.getUniqueId(), previous);
            player.sendMessage("直前の交換要求を処理中です。少し待ってからお試しください。");
            return;
        }
        UUID requestId = requestSink.publish(selection, player);
        if (selection.kind() == ExchangeKind.MATERIAL_BUYBACK) {
            pendingBuybacks.register(player.getUniqueId(), requestId);
        }
        if (selection.kind() == ExchangeKind.BALANCE) {
            player.sendMessage("XP残高を確認しています。まもなく本人だけに表示されます。");
        } else {
            player.sendMessage("交換を受け付けました。結果はまもなく本人だけに表示されます。");
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage("交換メニュー: /exchange");
        player.sendMessage("XP交換: /exchange xp <Minecraft XP量: 50|250|500|5000>");
        player.sendMessage("資源交換: /exchange resource <資源ID> <個数>（Tab補完あり）");
        player.sendMessage("両替: /exchange emerald-diamond <32|64>");
        player.sendMessage("両替: /exchange diamond-emerald <1|4>");
        player.sendMessage(
                "資源売却: /exchange buyback <1|2|4|8|16|max|all>（メインハンドで種類を指定）");
        player.sendMessage("XP残高: /exchange balance");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        if (arguments.length == 1) {
            return matching(
                    List.of(
                            "xp",
                            "resource",
                            "emerald-diamond",
                            "diamond-emerald",
                            "buyback",
                            "balance"),
                    arguments[0]);
        }
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2 && operation.equals("xp")) {
            return matching(List.of("50", "250", "500", "5000"), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("resource")) {
            return matching(catalog.resourceGroups().stream()
                    .map(group -> withoutMinecraftNamespace(group.target()))
                    .toList(), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("emerald-diamond")) {
            return matching(List.of("32", "64"), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("diamond-emerald")) {
            return matching(List.of("1", "4"), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("buyback")) {
            return matching(List.of("1", "2", "4", "8", "16", "max", "all"), arguments[1]);
        }
        if (arguments.length == 3 && operation.equals("resource")) {
            String resource = arguments[1].toLowerCase(Locale.ROOT);
            String itemId = resource.contains(":") ? resource : "minecraft:" + resource;
            List<String> counts = catalog.resourceGroups().stream()
                    .filter(group -> group.target().equals(itemId))
                    .findFirst()
                    .map(group -> group.options().stream()
                            .map(selection -> Integer.toString(selection.amount()))
                            .toList())
                    .orElseGet(List::of);
            return matching(counts, arguments[2]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(normalized)).toList();
    }

    private static String withoutMinecraftNamespace(String itemId) {
        return itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
    }
}
