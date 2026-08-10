package net.usapo.eventbridge;

import java.util.EnumMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class MiningBonus {
    private static final Map<Material, Reward> REWARDS = rewards();

    private MiningBonus() {}

    static Reward rewardFor(Material material) {
        return REWARDS.get(material);
    }

    static boolean isEligible(
            GameMode gameMode, boolean preferredTool, boolean hasSilkTouch) {
        return gameMode == GameMode.SURVIVAL && preferredTool && !hasSilkTouch;
    }

    static boolean isEligible(Player player, Block block) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        return isEligible(
                player.getGameMode(),
                block.isPreferredTool(tool),
                tool.containsEnchantment(Enchantment.SILK_TOUCH));
    }

    static void award(Player player, Reward reward, int awardedExperience) {
        if (awardedExperience <= 0) {
            throw new IllegalArgumentException("awardedExperience must be positive");
        }
        player.giveExp(awardedExperience);
        player.sendActionBar(Component.text(
                "⛏ " + reward.displayName() + "を採掘！ +" + awardedExperience + " XP",
                NamedTextColor.GOLD,
                TextDecoration.BOLD));
    }

    private static Map<Material, Reward> rewards() {
        EnumMap<Material, Reward> rewards = new EnumMap<>(Material.class);
        add(rewards, Material.COAL_ORE, "石炭鉱石", 5);
        add(rewards, Material.DEEPSLATE_COAL_ORE, "深層石炭鉱石", 5);
        add(rewards, Material.NETHER_QUARTZ_ORE, "ネザークォーツ鉱石", 5);
        add(rewards, Material.NETHER_GOLD_ORE, "ネザー金鉱石", 5);
        add(rewards, Material.IRON_ORE, "鉄鉱石", 10);
        add(rewards, Material.DEEPSLATE_IRON_ORE, "深層鉄鉱石", 10);
        add(rewards, Material.COPPER_ORE, "銅鉱石", 10);
        add(rewards, Material.DEEPSLATE_COPPER_ORE, "深層銅鉱石", 10);
        add(rewards, Material.GOLD_ORE, "金鉱石", 20);
        add(rewards, Material.DEEPSLATE_GOLD_ORE, "深層金鉱石", 20);
        add(rewards, Material.REDSTONE_ORE, "レッドストーン鉱石", 20);
        add(rewards, Material.DEEPSLATE_REDSTONE_ORE, "深層レッドストーン鉱石", 20);
        add(rewards, Material.LAPIS_ORE, "ラピスラズリ鉱石", 20);
        add(rewards, Material.DEEPSLATE_LAPIS_ORE, "深層ラピスラズリ鉱石", 20);
        add(rewards, Material.DIAMOND_ORE, "ダイヤモンド鉱石", 50);
        add(rewards, Material.DEEPSLATE_DIAMOND_ORE, "深層ダイヤモンド鉱石", 50);
        add(rewards, Material.EMERALD_ORE, "エメラルド鉱石", 50);
        add(rewards, Material.DEEPSLATE_EMERALD_ORE, "深層エメラルド鉱石", 50);
        add(rewards, Material.ANCIENT_DEBRIS, "古代の残骸", 100);
        return Map.copyOf(rewards);
    }

    private static void add(
            Map<Material, Reward> rewards, Material material, String displayName, int experience) {
        rewards.put(material, new Reward(displayName, experience));
    }

    record Reward(String displayName, int experience) {
        Reward {
            if (displayName.isBlank() || experience <= 0) {
                throw new IllegalArgumentException("mining reward must be positive and named");
            }
        }
    }

    @FunctionalInterface
    interface Rewarder {
        void award(Player player, Reward reward, int awardedExperience);
    }

    @FunctionalInterface
    interface EligibilityChecker {
        boolean isEligible(Player player, Block block);
    }
}
