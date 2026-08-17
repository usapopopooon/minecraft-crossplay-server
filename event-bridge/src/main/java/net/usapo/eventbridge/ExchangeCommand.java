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
    private final LongSupplier nowMillis;
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();

    ExchangeCommand(ExchangeRequestSink requestSink, BedrockExchangeFormGateway forms) {
        this(requestSink, forms, Clock.systemUTC()::millis);
    }

    ExchangeCommand(
            ExchangeRequestSink requestSink,
            BedrockExchangeFormGateway forms,
            LongSupplier nowMillis) {
        this.requestSink = requestSink;
        this.forms = forms;
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
            if (!forms.open(player, selection -> submit(player, selection))) {
                sendUsage(player);
            }
            return true;
        }

        parse(arguments).ifPresentOrElse(
                selection -> submit(player, selection),
                () -> sendUsage(player));
        return true;
    }

    private static Optional<ExchangeSelection> parse(String[] arguments) {
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (operation.equals("balance") && arguments.length == 1) {
            return Optional.of(ExchangeSelection.balance());
        }
        if (operation.equals("xp") && arguments.length == 2) {
            return parsePositiveInteger(arguments[1]).flatMap(ExchangeCatalog::findXp);
        }
        if (operation.equals("resource") && arguments.length == 3) {
            return parsePositiveInteger(arguments[2])
                    .flatMap(count -> ExchangeCatalog.findResource(arguments[1], count));
        }
        if (operation.equals("emerald-diamond") && arguments.length == 2) {
            return parsePositiveInteger(arguments[1]).flatMap(ExchangeCatalog::findEmeraldDiamond);
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
        long now = nowMillis.getAsLong();
        Long previous = lastRequests.put(player.getUniqueId(), now);
        if (previous != null && now - previous < REQUEST_COOLDOWN_MILLIS) {
            lastRequests.put(player.getUniqueId(), previous);
            player.sendMessage("直前の交換要求を処理中です。少し待ってからお試しください。");
            return;
        }
        requestSink.publish(selection, player);
        if (selection.kind() == ExchangeKind.BALANCE) {
            player.sendMessage("XP残高を確認しています。まもなく本人だけに表示されます。");
        } else {
            player.sendMessage("交換を受け付けました。結果はまもなく本人だけに表示されます。");
        }
    }

    private static void sendUsage(Player player) {
        player.sendMessage("交換メニュー: /exchange");
        player.sendMessage("XP交換: /exchange xp <Minecraft XP量: 50|250|500|5000>");
        player.sendMessage("資源交換: /exchange resource <diamond|emerald> <個数>");
        player.sendMessage("手持ち交換: /exchange emerald-diamond <32|64>");
        player.sendMessage("XP残高: /exchange balance");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        if (arguments.length == 1) {
            return matching(List.of("xp", "resource", "emerald-diamond", "balance"), arguments[0]);
        }
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2 && operation.equals("xp")) {
            return matching(List.of("50", "250", "500", "5000"), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("resource")) {
            return matching(List.of("diamond", "emerald"), arguments[1]);
        }
        if (arguments.length == 2 && operation.equals("emerald-diamond")) {
            return matching(List.of("32", "64"), arguments[1]);
        }
        if (arguments.length == 3 && operation.equals("resource")) {
            String resource = arguments[1].toLowerCase(Locale.ROOT);
            List<String> counts = resource.equals("diamond")
                    ? List.of("1", "3", "8", "16", "32", "64")
                    : resource.equals("emerald")
                            ? List.of("4", "16", "32", "64")
                            : List.of();
            return matching(counts, arguments[2]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(normalized)).toList();
    }
}
