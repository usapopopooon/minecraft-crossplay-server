package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

interface JavaMarketMenuGateway {
    boolean open(Player player, Consumer<MarketFormAction> actionHandler);
}
