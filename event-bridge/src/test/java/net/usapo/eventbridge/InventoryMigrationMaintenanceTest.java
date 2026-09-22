package net.usapo.eventbridge;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InventoryMigrationMaintenanceTest {
    @TempDir Path directory;

    @Test
    void markerOrMissingInventoriesBlocksLoginAndHealthyCompletedMigrationDoesNot() throws Exception {
        Path marker = directory.resolve("inventory-migration.pending");
        AsyncPlayerPreLoginEvent ready = mock(AsyncPlayerPreLoginEvent.class);
        new InventoryMigrationMaintenance(marker, () -> true).onPreLogin(ready);
        verifyNoInteractions(ready);
        AsyncPlayerPreLoginEvent missingPlugin = mock(AsyncPlayerPreLoginEvent.class);
        new InventoryMigrationMaintenance(marker, () -> false).onPreLogin(missingPlugin);
        verify(missingPlugin).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), anyString());
        Files.createFile(marker);
        AsyncPlayerPreLoginEvent maintenance = mock(AsyncPlayerPreLoginEvent.class);
        new InventoryMigrationMaintenance(marker, () -> true).onPreLogin(maintenance);
        verify(maintenance).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), anyString());
    }
}
