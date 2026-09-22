package net.usapo.eventbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/** Keeps players offline until the stopped-server inventory cutover is verified. */
final class InventoryMigrationMaintenance implements Listener {
    private final Path marker;
    private final BooleanSupplier ready;

    InventoryMigrationMaintenance(Path marker, BooleanSupplier ready) {
        this.marker = marker;
        this.ready = ready;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (Files.exists(marker) || !ready.getAsBoolean()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "持ち物のワールド分離を設定中です。しばらくしてから接続してください。");
        }
    }
}
