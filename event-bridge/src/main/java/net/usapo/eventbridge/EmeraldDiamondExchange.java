package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class EmeraldDiamondExchange {
    private static final Set<Integer> ALLOWED_EMERALD_COUNTS = Set.of(32, 64);
    private static final int EMERALDS_PER_DIAMOND = 32;
    private static final int HISTORY_LIMIT = 32;

    enum Status {
        COMPLETED("completed"),
        INSUFFICIENT_EMERALDS("insufficient_emeralds"),
        INVENTORY_FULL("inventory_full");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    record Result(Status status, int emeraldCount, int diamondCount, boolean duplicate) {}

    enum ItemKind {
        EMPTY,
        EMERALD,
        DIAMOND,
        OTHER
    }

    record InventorySlot(ItemKind kind, int amount) {
        InventorySlot {
            if (amount < 0 || (kind == ItemKind.EMPTY && amount != 0)) {
                throw new IllegalArgumentException("invalid inventory slot amount");
            }
        }

        static InventorySlot empty() {
            return new InventorySlot(ItemKind.EMPTY, 0);
        }
    }

    interface PlayerState {
        InventorySlot[] storageContents();

        void setStorageContents(InventorySlot[] contents);

        Integer completedEmeraldCount(UUID requestId);

        void markCompleted(UUID requestId, int emeraldCount);
    }

    Result exchange(PlayerState state, UUID requestId, int emeraldCount) {
        if (!ALLOWED_EMERALD_COUNTS.contains(emeraldCount)) {
            throw new IllegalArgumentException("emerald count must be 16, 32, or 64");
        }
        int diamondCount = emeraldCount / EMERALDS_PER_DIAMOND;
        Integer completedEmeraldCount = state.completedEmeraldCount(requestId);
        if (completedEmeraldCount != null) {
            if (completedEmeraldCount != emeraldCount) {
                throw new IllegalArgumentException("request ID was already used with another rate");
            }
            return new Result(Status.COMPLETED, emeraldCount, diamondCount, true);
        }

        InventorySlot[] planned = state.storageContents().clone();
        if (count(planned, ItemKind.EMERALD) < emeraldCount) {
            return new Result(Status.INSUFFICIENT_EMERALDS, emeraldCount, diamondCount, false);
        }
        removeEmeralds(planned, emeraldCount);
        if (!addDiamonds(planned, diamondCount)) {
            return new Result(Status.INVENTORY_FULL, emeraldCount, diamondCount, false);
        }

        state.setStorageContents(planned);
        state.markCompleted(requestId, emeraldCount);
        return new Result(Status.COMPLETED, emeraldCount, diamondCount, false);
    }

    private static int count(InventorySlot[] contents, ItemKind kind) {
        int total = 0;
        for (InventorySlot item : contents) {
            if (item.kind() == kind) {
                total += item.amount();
            }
        }
        return total;
    }

    private static void removeEmeralds(InventorySlot[] contents, int count) {
        List<Integer> emeraldSlots = new ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            InventorySlot item = contents[index];
            if (item.kind() == ItemKind.EMERALD) {
                emeraldSlots.add(index);
            }
        }
        emeraldSlots.sort(Comparator.comparingInt(index -> contents[index].amount()));

        int remaining = count;
        for (int index : emeraldSlots) {
            InventorySlot item = contents[index];
            int removed = Math.min(item.amount(), remaining);
            int updatedAmount = item.amount() - removed;
            contents[index] = updatedAmount == 0
                    ? InventorySlot.empty()
                    : new InventorySlot(ItemKind.EMERALD, updatedAmount);
            remaining -= removed;
            if (remaining == 0) {
                return;
            }
        }
        throw new IllegalStateException("emerald count changed during exchange planning");
    }

    private static boolean addDiamonds(InventorySlot[] contents, int count) {
        int remaining = count;
        int maxStackSize = 64;
        for (int index = 0; index < contents.length && remaining > 0; index++) {
            InventorySlot item = contents[index];
            if (item.kind() != ItemKind.DIAMOND) {
                continue;
            }
            int added = Math.min(maxStackSize - item.amount(), remaining);
            if (added > 0) {
                contents[index] = new InventorySlot(ItemKind.DIAMOND, item.amount() + added);
                remaining -= added;
            }
        }
        for (int index = 0; index < contents.length && remaining > 0; index++) {
            InventorySlot item = contents[index];
            if (item.kind() != ItemKind.EMPTY) {
                continue;
            }
            int added = Math.min(maxStackSize, remaining);
            contents[index] = new InventorySlot(ItemKind.DIAMOND, added);
            remaining -= added;
        }
        return remaining == 0;
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
                if (item == null || item.getType() == Material.AIR) {
                    slots[index] = InventorySlot.empty();
                } else if (item.getType() == Material.EMERALD) {
                    slots[index] = new InventorySlot(ItemKind.EMERALD, item.getAmount());
                } else if (item.getType() == Material.DIAMOND) {
                    slots[index] = new InventorySlot(ItemKind.DIAMOND, item.getAmount());
                } else {
                    slots[index] = new InventorySlot(ItemKind.OTHER, item.getAmount());
                }
            }
            return slots;
        }

        @Override
        public void setStorageContents(InventorySlot[] contents) {
            if (originalContents == null || originalContents.length != contents.length) {
                throw new IllegalStateException("inventory was not read before applying exchange");
            }
            ItemStack[] updated = new ItemStack[contents.length];
            for (int index = 0; index < contents.length; index++) {
                InventorySlot slot = contents[index];
                ItemStack original = originalContents[index];
                updated[index] = switch (slot.kind()) {
                    case EMPTY -> null;
                    case EMERALD, DIAMOND -> updateResourceItem(original, slot);
                    case OTHER -> original;
                };
            }
            player.getInventory().setStorageContents(updated);
        }

        private static ItemStack updateResourceItem(ItemStack original, InventorySlot slot) {
            Material material = slot.kind() == ItemKind.EMERALD
                    ? Material.EMERALD
                    : Material.DIAMOND;
            if (original != null && original.getType() == material) {
                return original.asQuantity(slot.amount());
            }
            return new ItemStack(material, slot.amount());
        }

        @Override
        public Integer completedEmeraldCount(UUID requestId) {
            return completedRequests().get(requestId.toString());
        }

        @Override
        public void markCompleted(UUID requestId, int emeraldCount) {
            LinkedHashMap<String, Integer> requests = completedRequests();
            requests.remove(requestId.toString());
            requests.put(requestId.toString(), emeraldCount);
            while (requests.size() > HISTORY_LIMIT) {
                Iterator<String> iterator = requests.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            List<String> entries = new ArrayList<>();
            requests.forEach((request, count) -> entries.add(request + "|" + count));
            player.getPersistentDataContainer()
                    .set(historyKey, PersistentDataType.STRING, String.join("\n", entries));
        }

        private LinkedHashMap<String, Integer> completedRequests() {
            String value = player.getPersistentDataContainer()
                    .get(historyKey, PersistentDataType.STRING);
            LinkedHashMap<String, Integer> requests = new LinkedHashMap<>();
            if (value == null || value.isBlank()) {
                return requests;
            }
            for (String entry : value.split("\n")) {
                try {
                    String[] fields = entry.split("\\|", -1);
                    if (fields.length != 2) {
                        continue;
                    }
                    String request = UUID.fromString(fields[0]).toString();
                    int emeraldCount = Integer.parseInt(fields[1]);
                    if (ALLOWED_EMERALD_COUNTS.contains(emeraldCount)) {
                        requests.put(request, emeraldCount);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore only malformed historical entries; new values are always UUIDs.
                }
            }
            return requests;
        }
    }
}
