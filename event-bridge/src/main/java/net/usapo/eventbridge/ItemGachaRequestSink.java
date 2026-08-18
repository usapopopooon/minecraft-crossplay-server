package net.usapo.eventbridge;

import org.bukkit.entity.Player;

@FunctionalInterface
interface ItemGachaRequestSink {
    void publish(ItemGachaCategory category, ItemGachaKind kind, Player player);
}
