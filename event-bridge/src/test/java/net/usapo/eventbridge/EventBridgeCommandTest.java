package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class EventBridgeCommandTest {
    @Test
    void materialBuybackReleaseIsDispatchedToTheBoundHandler() {
        VoiceBonusCommand voiceBonus = mock(VoiceBonusCommand.class);
        EmeraldDiamondCommand emeraldDiamond = mock(EmeraldDiamondCommand.class);
        MarketTransferCommand marketTransfer = mock(MarketTransferCommand.class);
        QuestControlCommand questControl = mock(QuestControlCommand.class);
        MaterialBuybackCommand materialBuyback = mock(MaterialBuybackCommand.class);
        EventBridgeCommand eventBridge = new EventBridgeCommand(
                voiceBonus, emeraldDiamond, marketTransfer, questControl, materialBuyback);
        CommandSender sender = mock(CommandSender.class);
        Command command = mock(Command.class);
        String[] arguments = {
            "material-buyback-release",
            "22222222-2222-4222-8222-222222222222",
            "11111111-1111-4111-8111-111111111111"
        };
        when(materialBuyback.onCommand(sender, command, "usapo-event-bridge", arguments))
                .thenReturn(true);

        assertTrue(eventBridge.onCommand(sender, command, "usapo-event-bridge", arguments));

        verify(materialBuyback).onCommand(sender, command, "usapo-event-bridge", arguments);
    }
}
