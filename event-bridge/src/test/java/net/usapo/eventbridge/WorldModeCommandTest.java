package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class WorldModeCommandTest {
    private final WorldModeCommand command = new WorldModeCommand();

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SURVIVAL"})
    void ordinaryPlayerCanToggleOwnModeWithoutElevatingPermissions(GameMode initial) {
        Player player = player("minecraft:resource", initial);
        GameMode expected = initial == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE;

        assertTrue(command.onCommand(player, null, "mode", new String[0]));

        assertEquals(expected, player.getGameMode());
        verify(player).setGameMode(expected);
        verify(player).sendMessage(expected == GameMode.CREATIVE
                ? "クリエイティブに切り替えました。"
                : "サバイバルに切り替えました。");
        verify(player, never()).isOp();
        verify(player, never()).setOp(anyBoolean());
        verify(player, never()).getInventory();
        verify(player, never()).getEnderChest();
        verify(player, never()).setTotalExperience(anyInt());
        verify(player, never()).setAllowFlight(anyBoolean());
        assertTrue(WorldEconomyPolicy.isRestricted(player),
                "Survival selection must not bypass world_2 economy restrictions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
            "minecraft:world_2_nether", "minecraft:world_2_the_end", "minecraft:world_2",
            "minecraft:resource_backup", "other:resource"})
    void allOtherDimensionsAreRejectedEvenForOperatorsAndMatchingDisplayNames(String key) {
        Player player = player(key, GameMode.SURVIVAL);
        when(player.isOp()).thenReturn(true);
        when(player.getWorld().getName()).thenReturn("world_2");

        assertTrue(command.onCommand(player, null, "mode", new String[0]));

        verify(player, never()).setGameMode(any());
        verify(player).sendMessage("このコマンドは world_2 でのみ使用できます（ネザー・エンドは対象外です）。");
    }

    @Test
    void explicitPermissionDenialCannotBeBypassedByCallingExecutorDirectly() {
        Player player = player("minecraft:resource", GameMode.SURVIVAL);
        when(player.hasPermission("usapo.mode.use")).thenReturn(false);

        assertTrue(command.onCommand(player, null, "mode", new String[0]));

        verify(player, never()).setGameMode(any());
        verify(player).sendMessage("このコマンドを使用する権限がありません。");
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"ADVENTURE", "SPECTATOR"})
    void doesNotOverrideAdministrativeGameModes(GameMode initial) {
        Player player = player("minecraft:resource", initial);

        assertTrue(command.onCommand(player, null, "mode", new String[0]));

        verify(player, never()).setGameMode(any());
        verify(player).sendMessage("サバイバルとクリエイティブの間でのみ切り替えできます。");
    }

    @Test
    void rejectsTargetsAndArgumentsInsteadOfChangingAnyPlayer() {
        for (String[] arguments : List.of(new String[] {"Steve"}, new String[] {"creative"},
                new String[] {"survival", "Steve"}, new String[] {"spectator"})) {
            Player player = player("minecraft:resource", GameMode.CREATIVE);

            assertTrue(command.onCommand(player, null, "mode", arguments));

            verify(player, never()).setGameMode(any());
            verify(player).sendMessage("使い方: /mode（自分のサバイバル／クリエイティブを切り替えます）");
        }
    }

    @Test
    void consoleCannotChangeAnyPlayer() {
        CommandSender console = mock(CommandSender.class);

        assertTrue(command.onCommand(console, null, "mode", new String[0]));

        verify(console).sendMessage("このコマンドはゲーム内のプレイヤーだけが使用できます。");
        verifyNoMoreInteractions(console);
    }

    @Test
    void cancellationByAnotherPluginIsNotReportedAsSuccessOrRetried() {
        Player player = player("minecraft:resource", GameMode.CREATIVE);
        doNothing().when(player).setGameMode(any());

        assertTrue(command.onCommand(player, null, "mode", new String[0]));

        assertEquals(GameMode.CREATIVE, player.getGameMode());
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).sendMessage("ゲームモードを切り替えられませんでした。管理者にご連絡ください。");
        verify(player, never()).sendMessage("サバイバルに切り替えました。");
    }

    @Test
    void tabCompletionNeverFallsBackToPlayerNamesOrAdministrativeModes() {
        assertEquals(List.of(), command.onTabComplete(mock(Player.class), null, "mode", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(mock(CommandSender.class), null,
                "mode", new String[] {"creative", ""}));
    }

    private static Player player(String key, GameMode initial) {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getKey()).thenReturn(NamespacedKey.fromString(key));
        when(player.hasPermission("usapo.mode.use")).thenReturn(true);
        AtomicReference<GameMode> mode = new AtomicReference<>(initial);
        when(player.getGameMode()).thenAnswer(ignored -> mode.get());
        doAnswer(invocation -> {
            mode.set(invocation.getArgument(0));
            return null;
        }).when(player).setGameMode(any());
        return player;
    }
}
