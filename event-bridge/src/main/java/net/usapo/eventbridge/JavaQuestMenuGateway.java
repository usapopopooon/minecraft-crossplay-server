package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

interface JavaQuestMenuGateway {
    boolean open(Player player, Consumer<QuestFormAction> actionHandler);
}
