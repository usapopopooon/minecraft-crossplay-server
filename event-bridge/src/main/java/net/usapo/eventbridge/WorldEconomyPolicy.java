package net.usapo.eventbridge;

import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Restricts the four shared economy features by inventory group, not game mode. */
final class WorldEconomyPolicy {
    static final String STATUS = "world_restricted";
    static final String MESSAGE =
            "このワールドではガチャ・フリマ・交換・クエストは利用できません。world_1側でご利用ください。";
    private static final Set<String> RESTRICTED = Set.of(
            "minecraft:resource", "minecraft:world_2_nether", "minecraft:world_2_the_end");

    private WorldEconomyPolicy() {}

    static boolean isRestricted(Player player) {
        if (player == null) return false;
        World world = player.getWorld();
        return world != null && world.getKey() != null
                && RESTRICTED.contains(world.getKey().toString());
    }

    static boolean denyIfRestricted(Player player) {
        if (!isRestricted(player)) return false;
        player.sendMessage(MESSAGE);
        return true;
    }
}
