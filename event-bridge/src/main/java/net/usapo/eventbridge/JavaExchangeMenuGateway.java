package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

interface JavaExchangeMenuGateway {
    boolean open(Player player, Consumer<ExchangeSelection> selectionHandler);
}
