package net.usapo.eventbridge;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class DiamondEmeraldCommand implements CommandExecutor {
    private static final Set<Integer> ALLOWED_DIAMOND_COUNTS = Set.of(1, 4);
    private static final int EMERALDS_PER_DIAMOND = 16;

    @FunctionalInterface
    interface ExchangeOperation {
        EmeraldDiamondExchange.Result exchange(Player player, UUID requestId, int diamondCount);
    }

    @FunctionalInterface
    interface SuccessNotifier {
        void notify(UUID requestId, Player player, int diamondCount, int emeraldCount);
    }

    private final Function<UUID, Player> playerLookup;
    private final ExchangeOperation exchangeOperation;
    private final SuccessNotifier successNotifier;

    DiamondEmeraldCommand(
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
        if (arguments.length != 4 || !arguments[0].equals("diamond-emerald-v1")) {
            return false;
        }

        UUID playerId;
        UUID requestId;
        int diamondCount;
        try {
            playerId = UUID.fromString(arguments[1]);
            diamondCount = Integer.parseInt(arguments[2]);
            requestId = UUID.fromString(arguments[3]);
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Invalid diamond exchange arguments");
            return true;
        }
        if (!ALLOWED_DIAMOND_COUNTS.contains(diamondCount)) {
            sender.sendMessage("Diamond count must be 1 or 4");
            return true;
        }

        Player player = playerLookup.apply(playerId);
        if (player == null) {
            sendResult(
                    sender,
                    requestId,
                    "player_offline",
                    diamondCount,
                    diamondCount * EMERALDS_PER_DIAMOND,
                    false);
            return true;
        }

        EmeraldDiamondExchange.Result result =
                exchangeOperation.exchange(player, requestId, diamondCount);
        if (result.status() == EmeraldDiamondExchange.Status.COMPLETED && !result.duplicate()) {
            successNotifier.notify(requestId, player, result.diamondCount(), result.emeraldCount());
        }
        sendResult(
                sender,
                requestId,
                result.status().wireName(),
                result.diamondCount(),
                result.emeraldCount(),
                result.duplicate());
        return true;
    }

    private static void sendResult(
            CommandSender sender,
            UUID requestId,
            String status,
            int diamondCount,
            int emeraldCount,
            boolean duplicate) {
        sender.sendMessage("USAPO_DIAMOND_EXCHANGE_RESULT|1|"
                + requestId
                + "|"
                + status
                + "|"
                + diamondCount
                + "|"
                + emeraldCount
                + "|"
                + (duplicate ? "duplicate" : "new"));
    }
}
