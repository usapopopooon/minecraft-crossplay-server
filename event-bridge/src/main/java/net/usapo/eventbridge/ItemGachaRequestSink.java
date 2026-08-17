package net.usapo.eventbridge;

import org.bukkit.entity.Player;

@FunctionalInterface
interface ItemGachaRequestSink {
    void publish(ItemGachaKind kind, Player player);
}
