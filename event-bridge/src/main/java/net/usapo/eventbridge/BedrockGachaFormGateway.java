package net.usapo.eventbridge;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

@FunctionalInterface
interface BedrockGachaFormGateway {
    boolean open(Player player, Consumer<ItemGachaKind> selectionHandler);
}
