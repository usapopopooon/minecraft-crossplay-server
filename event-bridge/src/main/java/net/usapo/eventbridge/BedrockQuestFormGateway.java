package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

interface BedrockQuestFormGateway {
    boolean open(Player player, Consumer<QuestFormAction> actionHandler);
}
