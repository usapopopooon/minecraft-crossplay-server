package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class EconomyAccessCommandTest {
    @Test
    void dispatchesToLivePlayerLookupAndRechecksWorldWithoutSpendingOrMessagingPlayer() {
        UUID playerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AtomicReference<UUID> lookedUp = new AtomicReference<>();
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        EconomyAccessCommand access = new EconomyAccessCommand(id -> {
            lookedUp.set(id);
            return player;
        });
        EventBridgeCommand bridge = new EventBridgeCommand(null, null, null, null,
                null, null, null, access);
        for (String key : List.of("overworld", "resource", "world_2_nether", "world_2_the_end",
                "the_nether", "the_end")) {
            when(world.getKey()).thenReturn(NamespacedKey.minecraft(key));
            CommandSender sender = mock(CommandSender.class);
            assertTrue(bridge.onCommand(sender, mock(Command.class), "usapo-event-bridge",
                    new String[] {"economy-access", playerId.toString(), requestId.toString()}));
            assertEquals(playerId, lookedUp.get());
            String expected = List.of("resource", "world_2_nether", "world_2_the_end").contains(key)
                    ? "world_restricted" : "allowed";
            verify(sender).sendMessage(EconomyAccessCommand.RESULT_PREFIX + requestId + "|" + expected);
        }
        verify(player, never()).getInventory();
        verify(player, never()).sendMessage(anyString());
        verify(player, never()).saveData();
    }

    @Test
    void reportsOfflineAndRejectsMalformedRequests() {
        EconomyAccessCommand access = new EconomyAccessCommand(ignored -> null);
        UUID request = UUID.randomUUID();
        CommandSender sender = mock(CommandSender.class);
        Command command = mock(Command.class);
        assertTrue(access.onCommand(sender, command, "usapo-event-bridge", new String[] {
                "economy-access", UUID.randomUUID().toString(), request.toString()}));
        verify(sender).sendMessage(EconomyAccessCommand.RESULT_PREFIX + request + "|player_offline");
        assertTrue(access.onCommand(sender, command, "usapo-event-bridge",
                new String[] {"economy-access", "invalid", request.toString()}));
        verify(sender).sendMessage("Invalid economy access arguments");
        assertFalse(access.onCommand(sender, command, "usapo-event-bridge", new String[] {"voice-bonus"}));
    }
}
