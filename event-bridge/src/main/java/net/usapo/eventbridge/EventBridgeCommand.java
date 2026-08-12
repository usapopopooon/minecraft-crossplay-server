package net.usapo.eventbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

final class EventBridgeCommand implements CommandExecutor {
    private final VoiceBonusCommand voiceBonus;
    private final EmeraldDiamondCommand emeraldDiamond;

    EventBridgeCommand(VoiceBonusCommand voiceBonus, EmeraldDiamondCommand emeraldDiamond) {
        this.voiceBonus = voiceBonus;
        this.emeraldDiamond = emeraldDiamond;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length > 0 && arguments[0].equals("voice-bonus")) {
            return voiceBonus.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0 && arguments[0].equals("emerald-diamond")) {
            return emeraldDiamond.onCommand(sender, command, label, arguments);
        }
        return false;
    }
}
