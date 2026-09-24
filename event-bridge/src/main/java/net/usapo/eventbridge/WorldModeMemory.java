package net.usapo.eventbridge;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

/** Remembers only world_2's C/S selection; other dimensions remain under Multiverse. */
final class WorldModeMemory implements Listener {
    private static final NamespacedKey WORLD_2 = NamespacedKey.minecraft("resource");
    private final NamespacedKey key;
    private final Set<UUID> flyingOnJoin = new HashSet<>();
    private final Set<UUID> failedRestores = new HashSet<>();

    WorldModeMemory(NamespacedKey key) {
        this.key = key;
    }

    void remember(Player player) {
        if (!isWorldTwo(player.getWorld())) return;
        // A rejected restoration is not a new choice by the player. Retain
        // their preference even if they leave or disconnect in fallback mode.
        if (failedRestores.contains(player.getUniqueId())) return;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SURVIVAL) {
            player.getPersistentDataContainer().set(key, PersistentDataType.STRING, mode.name());
        }
    }

    void rememberSelection(Player player) {
        failedRestores.remove(player.getUniqueId());
        remember(player);
    }

    private GameMode remembered(Player player) {
        String value = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if ("CREATIVE".equals(value)) return GameMode.CREATIVE;
        if ("SURVIVAL".equals(value)) return GameMode.SURVIVAL;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isWorldTwo(player.getWorld())) return;
        // Import an existing player's vanilla saved mode before Multiverse's
        // NORMAL join enforcement. No offline player-data rewrite is needed.
        if (remembered(player) == null && player.hasPlayedBefore()) remember(player);
        if (player.getGameMode() == GameMode.CREATIVE && player.isFlying()) {
            flyingOnJoin.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void afterJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean wasFlying = flyingOnJoin.remove(player.getUniqueId());
        restore(player);
        if (wasFlying && isWorldTwo(player.getWorld())
                && player.getGameMode() == GameMode.CREATIVE && player.getAllowFlight()) {
            player.setFlying(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remember(event.getPlayer());
        flyingOnJoin.remove(event.getPlayer().getUniqueId());
        failedRestores.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beforeTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled() || event.getTo() == null) return;
        if (isWorldTwo(event.getFrom().getWorld()) && !isWorldTwo(event.getTo().getWorld())) {
            remember(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeRespawn(PlayerRespawnEvent event) {
        // Later listeners can change the destination; saving the source mode
        // also for a same-world respawn is harmless.
        remember(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void afterWorldChange(PlayerChangedWorldEvent event) {
        restore(event.getPlayer());
    }

    private void restore(Player player) {
        if (!isWorldTwo(player.getWorld())) return;
        GameMode saved = remembered(player);
        GameMode target = saved == null ? GameMode.SURVIVAL : saved;
        // Production Multiverse enforces at NORMAL with delay=0; restore only
        // in world_2 after it, never retain creative in another dimension.
        if (player.getGameMode() == target) {
            failedRestores.remove(player.getUniqueId());
            return;
        }
        player.setGameMode(target);
        if (player.getGameMode() != target) {
            failedRestores.add(player.getUniqueId());
            player.sendMessage("world_2の以前のゲームモードを復元できませんでした。管理者にご連絡ください。");
        } else {
            failedRestores.remove(player.getUniqueId());
        }
    }

    private static boolean isWorldTwo(World world) {
        return world != null && WORLD_2.equals(world.getKey());
    }
}
