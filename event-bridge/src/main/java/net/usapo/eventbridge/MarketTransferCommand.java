package net.usapo.eventbridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

final class MarketTransferCommand implements CommandExecutor {
    static final String RESULT_PREFIX = "USAPO_MARKET_TRANSFER_RESULT|1|";
    private static final int HISTORY_LIMIT = 100;

    private final Function<UUID, Player> playerLookup;
    private final MarketRepository repository;
    private final NamespacedKey historyKey;

    MarketTransferCommand(
            Function<UUID, Player> playerLookup,
            MarketRepository repository,
            NamespacedKey historyKey) {
        this.playerLookup = playerLookup;
        this.repository = repository;
        this.historyKey = historyKey;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length != 4
                || (!arguments[0].equals("market-deliver")
                        && !arguments[0].equals("market-return"))) {
            return false;
        }
        boolean returning = arguments[0].equals("market-return");
        try {
            long listingId = Long.parseLong(arguments[1]);
            UUID recipientId = UUID.fromString(arguments[2]);
            UUID transferId = UUID.fromString(arguments[3]);
            if (listingId <= 0) {
                throw new IllegalArgumentException("invalid listing id");
            }
            transfer(sender, listingId, recipientId, transferId, returning);
        } catch (IllegalArgumentException error) {
            sender.sendMessage(RESULT_PREFIX + arguments[3]
                    + "|0|invalid_request|unknown|new");
        }
        return true;
    }

    private void transfer(
            CommandSender sender,
            long listingId,
            UUID recipientId,
            UUID transferId,
            boolean returning) {
        Optional<MarketListing> found = repository.find(listingId);
        if (found.isEmpty()) {
            result(sender, transferId, listingId, "unavailable", "unknown", false);
            return;
        }
        MarketListing listing = found.get();
        MarketListing.Status terminal = returning
                ? MarketListing.Status.CANCELLED
                : MarketListing.Status.SOLD;
        if (returning && !listing.sellerId().equals(recipientId)) {
            result(sender, transferId, listingId, "recipient_mismatch", "active", false);
            return;
        }
        if (listing.status() == terminal && transferId.equals(listing.transferId())) {
            result(sender, transferId, listingId, "completed", terminalName(terminal), true);
            return;
        }
        if (listing.status() != MarketListing.Status.ACTIVE
                && !(listing.status() == MarketListing.Status.DELIVERING
                        && transferId.equals(listing.transferId())
                        && recipientId.equals(listing.recipientId()))) {
            result(sender, transferId, listingId, "unavailable", statusName(listing), false);
            return;
        }
        Player recipient = playerLookup.apply(recipientId);
        if (recipient == null || !recipient.isOnline()) {
            result(sender, transferId, listingId, "player_offline", statusName(listing), false);
            return;
        }
        String history = readHistory(recipient);
        if (containsTransfer(history, transferId)) {
            try {
                recipient.saveData();
            } catch (RuntimeException error) {
                result(sender, transferId, listingId, "storage_error", "delivering", true);
                return;
            }
            try {
                repository.completeTransfer(listingId, transferId, terminal);
            } catch (IOException | IllegalStateException error) {
                result(sender, transferId, listingId, "storage_error", "delivering", true);
                return;
            }
            result(sender, transferId, listingId, "completed", terminalName(terminal), true);
            return;
        }

        PlayerInventory inventory = recipient.getInventory();
        ItemStack item = listing.item();
        if (!MarketItems.canFit(inventory, item)) {
            result(sender, transferId, listingId, "inventory_full", statusName(listing), false);
            return;
        }
        ItemStack[] snapshot = MarketItems.snapshot(inventory);
        try {
            repository.prepareTransfer(listingId, transferId, recipientId);
        } catch (IOException | RuntimeException error) {
            result(sender, transferId, listingId, "storage_error", statusName(listing), false);
            return;
        }
        try {
            Map<Integer, ItemStack> leftover = inventory.addItem(item);
            if (!leftover.isEmpty()) {
                inventory.setStorageContents(snapshot);
                repository.abortTransfer(listingId, transferId);
                result(sender, transferId, listingId, "inventory_full", "active", false);
                return;
            }
            writeHistory(recipient, history, transferId);
        } catch (IOException | RuntimeException error) {
            inventory.setStorageContents(snapshot);
            restoreHistory(recipient, history);
            try {
                repository.abortTransfer(listingId, transferId);
            } catch (IOException ignored) {
                // 次の同一transfer再試行で保存状態を解決する。
            }
            String status = repository.find(listingId)
                    .map(MarketTransferCommand::statusName)
                    .orElse("unknown");
            result(sender, transferId, listingId, "storage_error", status, false);
            return;
        }
        try {
            recipient.saveData();
        } catch (RuntimeException error) {
            // メモリ上のアイテムと履歴は維持し、再試行で保存成功を確認してから確定する。
            result(sender, transferId, listingId, "storage_error", "delivering", true);
            return;
        }

        try {
            repository.completeTransfer(listingId, transferId, terminal);
        } catch (IOException error) {
            // アイテムと履歴はplayerデータへ保存済み。再試行は履歴から確定できる。
            result(sender, transferId, listingId, "storage_error", "delivering", true);
            return;
        }
        result(sender, transferId, listingId, "completed", terminalName(terminal), false);
    }

    private String readHistory(Player player) {
        return player.getPersistentDataContainer().get(historyKey, PersistentDataType.STRING);
    }

    private static boolean containsTransfer(String history, UUID transferId) {
        if (history == null || history.isBlank()) {
            return false;
        }
        return Arrays.asList(history.split("\\n")).contains(transferId.toString());
    }

    private void writeHistory(Player player, String history, UUID transferId) {
        List<String> entries = new ArrayList<>();
        entries.add(transferId.toString());
        if (history != null && !history.isBlank()) {
            entries.addAll(Arrays.asList(history.split("\\n")));
        }
        if (entries.size() > HISTORY_LIMIT) {
            entries = entries.subList(0, HISTORY_LIMIT);
        }
        player.getPersistentDataContainer()
                .set(historyKey, PersistentDataType.STRING, String.join("\n", entries));
    }

    private void restoreHistory(Player player, String history) {
        if (history == null) {
            player.getPersistentDataContainer().remove(historyKey);
        } else {
            player.getPersistentDataContainer().set(historyKey, PersistentDataType.STRING, history);
        }
    }

    private static String statusName(MarketListing listing) {
        return listing.status().name().toLowerCase();
    }

    private static String terminalName(MarketListing.Status terminal) {
        return terminal.name().toLowerCase();
    }

    private static void result(
            CommandSender sender,
            UUID transferId,
            long listingId,
            String status,
            String listingStatus,
            boolean duplicate) {
        sender.sendMessage(RESULT_PREFIX + transferId + "|" + listingId + "|" + status + "|"
                + listingStatus + "|" + (duplicate ? "duplicate" : "new"));
    }
}
