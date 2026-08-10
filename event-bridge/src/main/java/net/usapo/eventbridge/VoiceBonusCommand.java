package net.usapo.eventbridge;

import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

final class VoiceBonusCommand implements CommandExecutor {
    enum UpdateResult {
        UPDATED,
        INVALID_UUID,
        INVALID_STATE,
        PLAYER_OFFLINE
    }

    private final VoiceBonusRegistry voiceBonuses;
    private final Predicate<UUID> isOnline;

    VoiceBonusCommand(VoiceBonusRegistry voiceBonuses, Predicate<UUID> isOnline) {
        this.voiceBonuses = voiceBonuses;
        this.isOnline = isOnline;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length != 3 || !arguments[0].equals("voice-bonus")) {
            return false;
        }
        UpdateResult result = update(arguments[1], arguments[2]);
        switch (result) {
            case INVALID_UUID -> sender.sendMessage("Invalid player UUID");
            case INVALID_STATE -> sender.sendMessage("State must be on or off");
            case PLAYER_OFFLINE -> sender.sendMessage("No player was found for UUID " + arguments[1]);
            case UPDATED -> sender.sendMessage(
                    "Voice XP bonus " + arguments[2] + " for " + arguments[1]);
        }
        return true;
    }

    UpdateResult update(String playerIdText, String state) {
        UUID playerId;
        try {
            playerId = UUID.fromString(playerIdText);
        } catch (IllegalArgumentException error) {
            return UpdateResult.INVALID_UUID;
        }
        switch (state) {
            case "on" -> {
                if (!isOnline.test(playerId)) {
                    return UpdateResult.PLAYER_OFFLINE;
                }
                voiceBonuses.activate(playerId);
            }
            case "off" -> voiceBonuses.deactivate(playerId);
            default -> { return UpdateResult.INVALID_STATE; }
        }
        return UpdateResult.UPDATED;
    }
}
