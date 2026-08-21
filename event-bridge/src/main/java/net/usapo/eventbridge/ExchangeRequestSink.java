package net.usapo.eventbridge;

import java.util.UUID;
import org.bukkit.entity.Player;

@FunctionalInterface
interface ExchangeRequestSink {
    UUID publish(ExchangeSelection selection, Player player);
}
