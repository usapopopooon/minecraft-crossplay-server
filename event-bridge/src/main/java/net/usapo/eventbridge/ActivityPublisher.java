package net.usapo.eventbridge;

import org.bukkit.entity.Player;

@FunctionalInterface
interface ActivityPublisher {
    void publish(ActivityKind kind, Player player, int amount);
}
