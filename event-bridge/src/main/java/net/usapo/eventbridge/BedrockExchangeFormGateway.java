package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

@FunctionalInterface
interface BedrockExchangeFormGateway {
    boolean open(Player player, Consumer<ExchangeSelection> selectionHandler);
}
