package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class MaterialBuybackExchange {
    private static final int HISTORY_LIMIT = 100;

    enum Status {
        COMPLETED("completed"),
        INSUFFICIENT_ITEMS("insufficient_items");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    record Result(Status status, String itemId, int itemCount, boolean duplicate) {}

    record InventorySlot(String itemId, int amount, boolean plain) {
        InventorySlot {
            if (amount < 0 || (itemId == null && amount != 0)) {
                throw new IllegalArgumentException("invalid buyback inventory slot");
            }
        }

        static InventorySlot empty() {
            return new InventorySlot(null, 0, true);
        }
    }

    record CompletedRequest(String itemId, int itemCount) {}

    interface PlayerState {
        InventorySlot[] storageContents();

        CompletedRequest completedRequest(UUID requestId);

        void commit(InventorySlot[] contents, UUID requestId, String itemId, int itemCount);
    }

    Result exchange(PlayerState state, UUID requestId, String itemId, int itemCount) {
        MaterialBuybackCatalog.Rate rate = MaterialBuybackCatalog.find(itemId)
                .orElseThrow(() -> new IllegalArgumentException("unsupported buyback item"));
        if (itemCount < MaterialBuybackCatalog.STACK_SIZE
                || itemCount > MaterialBuybackCatalog.MAX_ITEM_COUNT
                || itemCount % MaterialBuybackCatalog.STACK_SIZE != 0) {
            throw new IllegalArgumentException("buyback count must contain full stacks");
        }
        CompletedRequest completed = state.completedRequest(requestId);
        if (completed != null) {
            if (!completed.itemId().equals(itemId) || completed.itemCount() != itemCount) {
                throw new IllegalArgumentException("request ID was reused for another buyback");
            }
            return new Result(Status.COMPLETED, itemId, itemCount, true);
        }

        InventorySlot[] planned = state.storageContents().clone();
        if (count(planned, itemId) < itemCount) {
            return new Result(Status.INSUFFICIENT_ITEMS, itemId, itemCount, false);
        }
        remove(planned, itemId, itemCount);
        state.commit(planned, requestId, rate.itemId(), itemCount);
        return new Result(Status.COMPLETED, itemId, itemCount, false);
    }

    private static int count(InventorySlot[] contents, String itemId) {
        int total = 0;
        for (InventorySlot slot : contents) {
            if (slot.plain() && itemId.equals(slot.itemId())) {
                total += slot.amount();
            }
        }
        return total;
    }

    private static void remove(InventorySlot[] contents, String itemId, int count) {
        List<Integer> matching = new ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            InventorySlot slot = contents[index];
            if (slot.plain() && itemId.equals(slot.itemId())) {
                matching.add(index);
            }
        }
        matching.sort(Comparator.comparingInt(index -> contents[index].amount()));
        int remaining = count;
        for (int index : matching) {
            InventorySlot slot = contents[index];
            int removed = Math.min(slot.amount(), remaining);
            int updated = slot.amount() - removed;
            contents[index] = updated == 0
                    ? InventorySlot.empty()
                    : new InventorySlot(itemId, updated, true);
            remaining -= removed;
            if (remaining == 0) {
                return;
            }
        }
        throw new IllegalStateException("buyback inventory changed during planning");
    }

    static final class BukkitPlayerState implements PlayerState {
        private final Player player;
        private final NamespacedKey historyKey;
        private ItemStack[] originalContents;

        BukkitPlayerState(Player player, NamespacedKey historyKey) {
            this.player = player;
            this.historyKey = historyKey;
        }

        @Override
        public InventorySlot[] storageContents() {
            originalContents = player.getInventory().getStorageContents();
            InventorySlot[] slots = new InventorySlot[originalContents.length];
            for (int index = 0; index < originalContents.length; index++) {
                ItemStack item = originalContents[index];
                if (item == null || item.getType().isAir()) {
                    slots[index] = InventorySlot.empty();
                    continue;
                }
                slots[index] = new InventorySlot(
                        item.getType().getKey().toString(),
                        item.getAmount(),
                        !item.hasItemMeta());
            }
            return slots;
        }

        @Override
        public CompletedRequest completedRequest(UUID requestId) {
            return completedRequests().get(requestId.toString());
        }

        @Override
        public void commit(
                InventorySlot[] contents,
                UUID requestId,
                String itemId,
                int itemCount) {
            if (originalContents == null || originalContents.length != contents.length) {
                throw new IllegalStateException("inventory was not read before buyback");
            }
            ItemStack[] updated = new ItemStack[contents.length];
            for (int index = 0; index < contents.length; index++) {
                InventorySlot planned = contents[index];
                ItemStack original = originalContents[index];
                if (planned.itemId() == null) {
                    updated[index] = null;
                } else if (original != null
                        && original.getType().getKey().toString().equals(planned.itemId())
                        && original.getAmount() != planned.amount()) {
                    updated[index] = original.asQuantity(planned.amount());
                } else {
                    updated[index] = original;
                }
            }

            String previousHistory = player.getPersistentDataContainer()
                    .get(historyKey, PersistentDataType.STRING);
            player.getInventory().setStorageContents(updated);
            writeCompleted(requestId, itemId, itemCount);
            try {
                player.saveData();
            } catch (RuntimeException error) {
                player.getInventory().setStorageContents(originalContents);
                if (previousHistory == null) {
                    player.getPersistentDataContainer().remove(historyKey);
                } else {
                    player.getPersistentDataContainer()
                            .set(historyKey, PersistentDataType.STRING, previousHistory);
                }
                try {
                    player.saveData();
                } catch (RuntimeException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }

        private void writeCompleted(UUID requestId, String itemId, int itemCount) {
            LinkedHashMap<String, CompletedRequest> requests = completedRequests();
            requests.remove(requestId.toString());
            requests.put(requestId.toString(), new CompletedRequest(itemId, itemCount));
            while (requests.size() > HISTORY_LIMIT) {
                Iterator<String> iterator = requests.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            List<String> entries = new ArrayList<>();
            requests.forEach((request, completed) -> entries.add(request
                    + "|" + completed.itemId() + "|" + completed.itemCount()));
            player.getPersistentDataContainer()
                    .set(historyKey, PersistentDataType.STRING, String.join("\n", entries));
        }

        private LinkedHashMap<String, CompletedRequest> completedRequests() {
            String value = player.getPersistentDataContainer()
                    .get(historyKey, PersistentDataType.STRING);
            LinkedHashMap<String, CompletedRequest> requests = new LinkedHashMap<>();
            if (value == null || value.isBlank()) {
                return requests;
            }
            for (String entry : value.split("\n")) {
                try {
                    String[] fields = entry.split("\\|", -1);
                    if (fields.length != 3 || MaterialBuybackCatalog.find(fields[1]).isEmpty()) {
                        continue;
                    }
                    String requestId = UUID.fromString(fields[0]).toString();
                    int count = Integer.parseInt(fields[2]);
                    if (count >= MaterialBuybackCatalog.STACK_SIZE
                            && count % MaterialBuybackCatalog.STACK_SIZE == 0) {
                        requests.put(requestId, new CompletedRequest(fields[1], count));
                    }
                } catch (IllegalArgumentException ignored) {
                    // 壊れた古い履歴だけを無視する。
                }
            }
            return requests;
        }
    }
}
