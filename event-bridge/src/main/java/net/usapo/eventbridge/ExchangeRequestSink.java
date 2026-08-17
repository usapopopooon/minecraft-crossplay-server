package net.usapo.eventbridge;

import org.bukkit.entity.Player;

@FunctionalInterface
interface ExchangeRequestSink {
    void publish(ExchangeSelection selection, Player player);
}
