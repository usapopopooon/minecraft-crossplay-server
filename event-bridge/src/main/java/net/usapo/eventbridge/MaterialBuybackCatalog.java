package net.usapo.eventbridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class MaterialBuybackCatalog {
    static final int STACK_SIZE = 64;
    static final int MAX_ITEM_COUNT = 36 * STACK_SIZE;
    static final int DAILY_LIMIT_XP = 3_000;
    private static final List<Integer> STACK_OPTIONS = List.of(1, 2, 4, 8, 16);
    static final Rate EMERALD_RATE =
            new Rate(Material.EMERALD, "minecraft:emerald", "エメラルド", 500);
    static final List<Rate> MATERIAL_RATES = List.of(
            new Rate(Material.DIRT, "minecraft:dirt", "土", 30),
            new Rate(Material.SAND, "minecraft:sand", "砂", 40),
            new Rate(Material.SANDSTONE, "minecraft:sandstone", "砂岩", 50),
            new Rate(Material.DEEPSLATE, "minecraft:deepslate", "深層岩", 35),
            new Rate(
                    Material.COBBLED_DEEPSLATE,
                    "minecraft:cobbled_deepslate",
                    "深層岩の丸石",
                    35),
            new Rate(Material.TUFF, "minecraft:tuff", "凝灰岩", 40));
    static final List<Rate> RATES = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(EMERALD_RATE), MATERIAL_RATES.stream())
            .toList();

    private MaterialBuybackCatalog() {}

    static Optional<Rate> find(Material material) {
        return RATES.stream().filter(rate -> rate.material() == material).findFirst();
    }

    static Optional<Rate> find(String itemId) {
        return RATES.stream().filter(rate -> rate.itemId().equals(itemId)).findFirst();
    }

    static int plainCount(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isPlain(item, material)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    static boolean isPlain(ItemStack item, Material material) {
        return item != null
                && item.getType() == material
                && item.getAmount() > 0
                && !item.hasItemMeta();
    }

    static ExchangeSelection selection(Rate rate, int itemCount) {
        if (itemCount < STACK_SIZE
                || itemCount > MAX_ITEM_COUNT
                || itemCount % STACK_SIZE != 0) {
            throw new IllegalArgumentException("buyback count must contain full stacks");
        }
        int rewardXp = Math.multiplyExact(
                itemCount / STACK_SIZE, rate.rewardXpPerStack());
        return new ExchangeSelection(
                ExchangeKind.MATERIAL_BUYBACK,
                rate.itemId(),
                rate.itemName(),
                itemCount,
                0,
                rewardXp,
                "通常の" + rate.itemName() + " x" + itemCount + "を回収 → "
                        + rewardXp + " サーバーXP獲得");
    }

    static List<QuantityOption> quantityOptions(Rate rate, int available) {
        int exchangeable = available / STACK_SIZE * STACK_SIZE;
        if (exchangeable < STACK_SIZE) {
            return List.of();
        }
        int singleTradeMaximum = Math.min(exchangeable, maximumDailyItemCount(rate));
        LinkedHashSet<Integer> counts = new LinkedHashSet<>();
        STACK_OPTIONS.stream()
                .map(stacks -> stacks * STACK_SIZE)
                .filter(count -> count <= singleTradeMaximum)
                .forEach(counts::add);
        counts.add(singleTradeMaximum);
        return counts.stream()
                .map(count -> new QuantityOption(
                        count,
                        quantityLabel(
                                count,
                                exchangeable,
                                singleTradeMaximum)))
                .toList();
    }

    static int maximumDailyItemCount(Rate rate) {
        return DAILY_LIMIT_XP / rate.rewardXpPerStack() * STACK_SIZE;
    }

    private static String quantityLabel(
            int count, int exchangeable, int singleTradeMaximum) {
        int stacks = count / STACK_SIZE;
        if (count == exchangeable) {
            return "交換可能分をすべて（" + stacks + "スタック・" + count + "個）";
        }
        if (singleTradeMaximum < exchangeable && count == singleTradeMaximum) {
            return "1回で選べる最大（" + stacks + "スタック・" + count + "個）";
        }
        return stacks + "スタック（" + count + "個）";
    }

    record QuantityOption(int itemCount, String label) {}

    record Rate(Material material, String itemId, String itemName, int rewardXpPerStack) {
        Rate {
            if (material == null
                    || material == Material.AIR
                    || !itemId.startsWith("minecraft:")
                    || itemName.isBlank()
                    || rewardXpPerStack <= 0) {
                throw new IllegalArgumentException("invalid material buyback rate");
            }
        }
    }
}
