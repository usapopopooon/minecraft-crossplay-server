package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class WorldEconomyPolicyTest {
    @Test
    void restrictsExactInventoryGroupRegardlessOfAliasModeOrOperator() {
        for (String key : List.of("resource", "world_2_nether", "world_2_the_end")) {
            Player player = mock(Player.class);
            World world = mock(World.class);
            when(player.getWorld()).thenReturn(world);
            when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
            assertTrue(WorldEconomyPolicy.denyIfRestricted(player), key);
            verify(player).sendMessage(WorldEconomyPolicy.MESSAGE);
            verify(player, never()).getGameMode();
            verify(player, never()).isOp();
        }
    }

    @Test
    void preservesAllOtherDimensionsAndNeverMutatesInventory() {
        for (String key : List.of("overworld", "the_nether", "the_end", "resource_backup")) {
            Player player = mock(Player.class);
            World world = mock(World.class);
            when(player.getWorld()).thenReturn(world);
            when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
            assertFalse(WorldEconomyPolicy.denyIfRestricted(player), key);
            verify(player, never()).sendMessage(anyString());
            verify(player, never()).getInventory();
        }
        assertFalse(WorldEconomyPolicy.isRestricted(null));
    }
}
