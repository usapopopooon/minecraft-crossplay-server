package net.usapo.eventbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.mvplugins.multiverse.core.event.MVTeleportDestinationEvent;

/** Keeps Multiverse shortcuts from bypassing vanilla dimension progression. */
final class MultiverseTravelGuard implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDestinationTeleport(MVTeleportDestinationEvent event) {
        if (event.isCancelled() || !(event.getTeleportee() instanceof Player)) {
            return;
        }
        // Resolve the actual destination, not command text: player, anchor, bed,
        // coordinate, and world destinations must all follow the same policy.
        Location destination = event.getDestination().getLocation(event.getTeleportee()).getOrNull();
        if (destination == null || destination.getWorld() == null) {
            return;
        }
        World.Environment environment = destination.getWorld().getEnvironment();
        if (environment != World.Environment.NETHER && environment != World.Environment.THE_END) {
            return;
        }
        event.setCancelled(true);
        if (event.getTeleporter() != null) {
            event.getTeleporter().sendMessage(Component.text(
                    "ネザー・エンドへは通常のポータルから移動してください。", NamedTextColor.RED));
        }
    }
}
