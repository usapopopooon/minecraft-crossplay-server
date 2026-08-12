package net.usapo.eventbridge;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class EmeraldDiamondCommand implements CommandExecutor {
    private static final Set<Integer> ALLOWED_EMERALD_COUNTS = Set.of(16, 32, 64);

    @FunctionalInterface
    interface ExchangeOperation {
        EmeraldDiamondExchange.Result exchange(Player player, UUID requestId, int emeraldCount);
    }

    @FunctionalInterface
    interface SuccessNotifier {
        void notify(UUID requestId, Player player, int emeraldCount, int diamondCount);
    }

    private final Function<UUID, Player> playerLookup;
    private final ExchangeOperation exchangeOperation;
    private final SuccessNotifier successNotifier;

    EmeraldDiamondCommand(
            Function<UUID, Player> playerLookup,
            ExchangeOperation exchangeOperation,
            SuccessNotifier successNotifier) {
        this.playerLookup = playerLookup;
        this.exchangeOperation = exchangeOperation;
        this.successNotifier = successNotifier;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length != 4 || !arguments[0].equals("emerald-diamond")) {
            return false;
        }

        UUID playerId;
        UUID requestId;
        int emeraldCount;
        try {
            playerId = UUID.fromString(arguments[1]);
            emeraldCount = Integer.parseInt(arguments[2]);
            requestId = UUID.fromString(arguments[3]);
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Invalid emerald exchange arguments");
            return true;
        }
        if (!ALLOWED_EMERALD_COUNTS.contains(emeraldCount)) {
            sender.sendMessage("Emerald count must be 16, 32, or 64");
            return true;
        }

        Player player = playerLookup.apply(playerId);
        if (player == null) {
            sendResult(sender, requestId, "player_offline", emeraldCount, emeraldCount / 16, false);
            return true;
        }

        EmeraldDiamondExchange.Result result =
                exchangeOperation.exchange(player, requestId, emeraldCount);
        if (result.status() == EmeraldDiamondExchange.Status.COMPLETED && !result.duplicate()) {
            successNotifier.notify(requestId, player, result.emeraldCount(), result.diamondCount());
        }
        sendResult(
                sender,
                requestId,
                result.status().wireName(),
                result.emeraldCount(),
                result.diamondCount(),
                result.duplicate());
        return true;
    }

    private static void sendResult(
            CommandSender sender,
            UUID requestId,
            String status,
            int emeraldCount,
            int diamondCount,
            boolean duplicate) {
        sender.sendMessage("USAPO_EMERALD_EXCHANGE_RESULT|1|"
                + requestId
                + "|"
                + status
                + "|"
                + emeraldCount
                + "|"
                + diamondCount
                + "|"
                + (duplicate ? "duplicate" : "new"));
    }
}
