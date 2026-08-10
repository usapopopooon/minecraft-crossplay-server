package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class MiningBonusTest {
    @Test
    void everyOreMapsToTheConfiguredFixedReward() {
        Map<Material, Integer> expected = Map.ofEntries(
                Map.entry(Material.COAL_ORE, 5),
                Map.entry(Material.DEEPSLATE_COAL_ORE, 5),
                Map.entry(Material.NETHER_QUARTZ_ORE, 5),
                Map.entry(Material.NETHER_GOLD_ORE, 5),
                Map.entry(Material.IRON_ORE, 10),
                Map.entry(Material.DEEPSLATE_IRON_ORE, 10),
                Map.entry(Material.COPPER_ORE, 10),
                Map.entry(Material.DEEPSLATE_COPPER_ORE, 10),
                Map.entry(Material.GOLD_ORE, 20),
                Map.entry(Material.DEEPSLATE_GOLD_ORE, 20),
                Map.entry(Material.REDSTONE_ORE, 20),
                Map.entry(Material.DEEPSLATE_REDSTONE_ORE, 20),
                Map.entry(Material.LAPIS_ORE, 20),
                Map.entry(Material.DEEPSLATE_LAPIS_ORE, 20),
                Map.entry(Material.DIAMOND_ORE, 50),
                Map.entry(Material.DEEPSLATE_DIAMOND_ORE, 50),
                Map.entry(Material.EMERALD_ORE, 50),
                Map.entry(Material.DEEPSLATE_EMERALD_ORE, 50),
                Map.entry(Material.ANCIENT_DEBRIS, 100));

        expected.forEach((material, experience) ->
                assertEquals(experience, MiningBonus.rewardFor(material).experience(), material.name()));
        assertNull(MiningBonus.rewardFor(Material.DIAMOND_BLOCK));
        assertNull(MiningBonus.rewardFor(Material.STONE));
    }

    @Test
    void onlySurvivalPreferredNonSilkMiningIsEligible() {
        assertTrue(MiningBonus.isEligible(GameMode.SURVIVAL, true, false));
        assertFalse(MiningBonus.isEligible(GameMode.CREATIVE, true, false));
        assertFalse(MiningBonus.isEligible(GameMode.ADVENTURE, true, false));
        assertFalse(MiningBonus.isEligible(GameMode.SURVIVAL, false, false));
        assertFalse(MiningBonus.isEligible(GameMode.SURVIVAL, true, true));
    }

    @Test
    void awardAddsExperienceAndSendsOnlyPersonalActionBarFeedback() {
        List<String> calls = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, methodArguments) -> {
                    calls.add(method.getName());
                    arguments.add(methodArguments[0]);
                    return EventLogPublisherTest.defaultValue(method.getReturnType());
                });
        MiningBonus.Reward reward = MiningBonus.rewardFor(Material.DIAMOND_ORE);

        MiningBonus.award(player, reward, 50);

        assertEquals(List.of("giveExp", "sendActionBar"), calls);
        assertEquals(50, arguments.get(0));
        assertEquals(
                Component.text(
                        "⛏ ダイヤモンド鉱石を採掘！ +50 XP",
                        NamedTextColor.GOLD,
                        TextDecoration.BOLD),
                arguments.get(1));
    }
}
