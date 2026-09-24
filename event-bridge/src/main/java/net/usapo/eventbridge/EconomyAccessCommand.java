package net.usapo.eventbridge;

import java.util.UUID;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Read-only, main-thread check for Discord before reserving money or a reward. */
final class EconomyAccessCommand implements CommandExecutor {
    static final String RESULT_PREFIX = "USAPO_ECONOMY_ACCESS_RESULT|1|";
    private final Function<UUID, Player> playerLookup;

    EconomyAccessCommand(Function<UUID, Player> playerLookup) {
        this.playerLookup = playerLookup;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] arguments) {
        if (arguments.length != 3 || !arguments[0].equals("economy-access")) return false;
        try {
            UUID playerId = UUID.fromString(arguments[1]);
            UUID requestId = UUID.fromString(arguments[2]);
            Player player = playerLookup.apply(playerId);
            String status = player == null || !player.isOnline() ? "player_offline"
                    : WorldEconomyPolicy.isRestricted(player) ? WorldEconomyPolicy.STATUS : "allowed";
            sender.sendMessage(RESULT_PREFIX + requestId + "|" + status);
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Invalid economy access arguments");
        }
        return true;
    }
}
