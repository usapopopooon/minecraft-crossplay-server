package net.usapo.eventbridge;

import org.bukkit.entity.Player;

interface BedrockWorldTravelPrompt {
    boolean isBedrockPlayer(Player player);

    boolean open(Player player, String destinationLabel, Runnable confirm, Runnable cancel);
}
