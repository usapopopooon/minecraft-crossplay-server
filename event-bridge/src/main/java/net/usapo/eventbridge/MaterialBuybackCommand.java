package net.usapo.eventbridge;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class MaterialBuybackCommand implements CommandExecutor {
    static final String RESULT_PREFIX = "USAPO_MATERIAL_BUYBACK_RESULT|1|";
    static final String RELEASE_RESULT_PREFIX = "USAPO_MATERIAL_BUYBACK_RELEASE_RESULT|1|";

    private final Function<UUID, Player> playerLookup;
    private final NamespacedKey historyKey;
    private final MaterialBuybackExchange exchange;
    private final Logger logger;
    private final MaterialBuybackPendingRegistry pendingRegistry;

    MaterialBuybackCommand(
            Function<UUID, Player> playerLookup,
            NamespacedKey historyKey,
            MaterialBuybackExchange exchange,
            MaterialBuybackPendingRegistry pendingRegistry,
            Logger logger) {
        this.playerLookup = playerLookup;
        this.historyKey = historyKey;
        this.exchange = exchange;
        this.pendingRegistry = pendingRegistry;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length == 3 && arguments[0].equals("material-buyback-release")) {
            release(sender, arguments);
            return true;
        }
        if (arguments.length != 5 || !arguments[0].equals("material-buyback")) {
            return false;
        }
        UUID playerId;
        UUID requestId;
        int itemCount;
        try {
            playerId = UUID.fromString(arguments[1]);
            String itemId = arguments[2];
            itemCount = Integer.parseInt(arguments[3]);
            requestId = UUID.fromString(arguments[4]);
            if (MaterialBuybackCatalog.find(itemId).isEmpty()
                    || itemCount < MaterialBuybackCatalog.STACK_SIZE
                    || itemCount > MaterialBuybackCatalog.MAX_ITEM_COUNT
                    || itemCount % MaterialBuybackCatalog.STACK_SIZE != 0) {
                throw new IllegalArgumentException("invalid material buyback selection");
            }
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Invalid material buyback arguments");
            return true;
        }

        String itemId = arguments[2];
        Player player = playerLookup.apply(playerId);
        if (player == null || !player.isOnline()) {
            sendResult(sender, requestId, "player_offline", itemId, itemCount, false);
            return true;
        }
        try {
            MaterialBuybackExchange.Result result = exchange.exchange(
                    new MaterialBuybackExchange.BukkitPlayerState(player, historyKey),
                    requestId,
                    itemId,
                    itemCount);
            sendResult(
                    sender,
                    requestId,
                    result.status().wireName(),
                    result.itemId(),
                    result.itemCount(),
                    result.duplicate());
        } catch (RuntimeException error) {
            logger.log(
                    Level.SEVERE,
                    "Could not persist material buyback request=" + requestId
                            + " player=" + playerId,
                    error);
            sendResult(sender, requestId, "storage_error", itemId, itemCount, false);
        }
        return true;
    }

    private void release(CommandSender sender, String[] arguments) {
        try {
            UUID playerId = UUID.fromString(arguments[1]);
            UUID requestId = UUID.fromString(arguments[2]);
            MaterialBuybackPendingRegistry.ReleaseStatus status =
                    pendingRegistry.release(playerId, requestId);
            sender.sendMessage(RELEASE_RESULT_PREFIX
                    + requestId + "|" + playerId + "|" + status.wireName());
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Invalid material buyback release arguments");
        }
    }

    private static void sendResult(
            CommandSender sender,
            UUID requestId,
            String status,
            String itemId,
            int itemCount,
            boolean duplicate) {
        sender.sendMessage(RESULT_PREFIX
                + requestId + "|" + status + "|" + itemId + "|" + itemCount + "|"
                + (duplicate ? "duplicate" : "new"));
    }
}
