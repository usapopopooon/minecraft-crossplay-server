package net.usapo.eventbridge;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Self-only toggle for the normal world_2 dimension, never its Nether or End. */
final class WorldModeCommand implements CommandExecutor, TabCompleter {
    private static final NamespacedKey WORLD_2 = NamespacedKey.minecraft("resource");
    private final WorldModeMemory memory;

    WorldModeCommand(WorldModeMemory memory) {
        this.memory = memory;
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
        if (!player.hasPermission("usapo.mode.use")) {
            player.sendMessage("このコマンドを使用する権限がありません。");
            return true;
        }
        if (!WORLD_2.equals(player.getWorld().getKey())) {
            player.sendMessage("このコマンドは world_2 でのみ使用できます（ネザー・エンドは対象外です）。");
            return true;
        }
        if (arguments.length != 0) {
            player.sendMessage("使い方: /mode（自分のサバイバル／クリエイティブを切り替えます）");
            return true;
        }
        GameMode target = switch (player.getGameMode()) {
            case CREATIVE -> GameMode.SURVIVAL;
            case SURVIVAL -> GameMode.CREATIVE;
            default -> null;
        };
        if (target == null) {
            player.sendMessage("サバイバルとクリエイティブの間でのみ切り替えできます。");
            return true;
        }
        player.setGameMode(target);
        // Paper's game-mode event can be cancelled; do not force or retry the change.
        if (player.getGameMode() != target) {
            player.sendMessage("ゲームモードを切り替えられませんでした。管理者にご連絡ください。");
            return true;
        }
        memory.rememberSelection(player);
        player.sendMessage(target == GameMode.CREATIVE
                ? "クリエイティブに切り替えました。" : "サバイバルに切り替えました。");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments) {
        return List.of();
    }
}
