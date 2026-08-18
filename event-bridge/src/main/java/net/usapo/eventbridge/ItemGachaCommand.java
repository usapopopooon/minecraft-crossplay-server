package net.usapo.eventbridge;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class ItemGachaCommand implements CommandExecutor, TabCompleter {
    private static final long REQUEST_COOLDOWN_MILLIS = 2_000;

    private final ItemGachaRequestSink requestSink;
    private final BedrockGachaFormGateway forms;
    private final LongSupplier nowMillis;
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();

    ItemGachaCommand(ItemGachaRequestSink requestSink, BedrockGachaFormGateway forms) {
        this(requestSink, forms, Clock.systemUTC()::millis);
    }

    ItemGachaCommand(
            ItemGachaRequestSink requestSink,
            BedrockGachaFormGateway forms,
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
            if (!forms.open(player, selection -> submit(
                    player, selection.category(), selection.kind()))) {
                sendUsage(player);
            }
            return true;
        }
        if (arguments.length == 1) {
            ItemGachaKind.fromCommandArgument(arguments[0]).ifPresentOrElse(
                    kind -> submit(player, ItemGachaCategory.ALL, kind),
                    () -> sendUsage(player));
            return true;
        }
        if (arguments.length != 2) {
            sendUsage(player);
            return true;
        }
        ItemGachaCategory category = ItemGachaCategory.fromCommandArgument(arguments[0])
                .orElse(null);
        ItemGachaKind kind = ItemGachaKind.fromCommandArgument(arguments[1]).orElse(null);
        if (category == null || kind == null) {
            sendUsage(player);
            return true;
        }
        submit(player, category, kind);
        return true;
    }

    private void submit(Player player, ItemGachaCategory category, ItemGachaKind kind) {
        if (!player.isOnline()) {
            return;
        }
        long now = nowMillis.getAsLong();
        Long previous = lastRequests.put(player.getUniqueId(), now);
        if (previous != null && now - previous < REQUEST_COOLDOWN_MILLIS) {
            lastRequests.put(player.getUniqueId(), previous);
            player.sendMessage("直前のガチャ要求を処理中です。少し待ってからお試しください。");
            return;
        }
        requestSink.publish(category, kind, player);
        player.sendMessage(category.label() + "・" + kind.label()
                + "ガチャを受け付けました。結果はまもなく表示されます。");
    }

    private static void sendUsage(Player player) {
        player.sendMessage("おまかせ: /gacha normal (100 XP) または "
                + "/gacha rare (R以上確定・1,000 XP)");
        player.sendMessage("種類指定: /gacha <resource|adventure|equipment> <normal|rare>");
        player.sendMessage("両方を合わせて日本時間0:00更新・1日3回までです。");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("normal", "rare", "resource", "adventure", "equipment").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (arguments.length == 2
                && ItemGachaCategory.fromCommandArgument(arguments[0]).isPresent()) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return List.of("normal", "rare").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
