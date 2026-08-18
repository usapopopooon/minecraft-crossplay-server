package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

interface BedrockMarketFormGateway {
    boolean open(Player player, Consumer<MarketFormAction> actionHandler);
}
