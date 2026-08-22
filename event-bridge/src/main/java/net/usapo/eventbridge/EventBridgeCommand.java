package net.usapo.eventbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

final class EventBridgeCommand implements CommandExecutor {
    private final VoiceBonusCommand voiceBonus;
    private final EmeraldDiamondCommand emeraldDiamond;
    private final DiamondEmeraldCommand diamondEmerald;
    private final MarketTransferCommand marketTransfer;
    private final QuestControlCommand questControl;
    private final MaterialBuybackCommand materialBuyback;
    private final ResourceCatalogCommand resourceCatalog;

    EventBridgeCommand(
            VoiceBonusCommand voiceBonus,
            EmeraldDiamondCommand emeraldDiamond,
            DiamondEmeraldCommand diamondEmerald,
            MarketTransferCommand marketTransfer,
            QuestControlCommand questControl,
            MaterialBuybackCommand materialBuyback,
            ResourceCatalogCommand resourceCatalog) {
        this.voiceBonus = voiceBonus;
        this.emeraldDiamond = emeraldDiamond;
        this.diamondEmerald = diamondEmerald;
        this.marketTransfer = marketTransfer;
        this.questControl = questControl;
        this.materialBuyback = materialBuyback;
        this.resourceCatalog = resourceCatalog;
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
        if (arguments.length > 0 && arguments[0].equals("emerald-diamond-v2")) {
            return emeraldDiamond.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0 && arguments[0].equals("diamond-emerald-v1")) {
            return diamondEmerald.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0 && arguments[0].startsWith("market-")) {
            return marketTransfer.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0 && arguments[0].startsWith("quest-")) {
            return questControl.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0
                && (arguments[0].equals("material-buyback")
                        || arguments[0].equals("material-buyback-release"))) {
            return materialBuyback.onCommand(sender, command, label, arguments);
        }
        if (arguments.length > 0
                && (arguments[0].equals("resource-catalog-sync")
                        || arguments[0].equals("resource-pack-validate"))) {
            return resourceCatalog.onCommand(sender, command, label, arguments);
        }
        return false;
    }
}
