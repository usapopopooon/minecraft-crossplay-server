package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

final class WorldModeRegistrationTest {
    @Test
    void grantsOnlyTheDedicatedSelfTogglePermissionToOrdinaryPlayers() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            PluginDescriptionFile description = new PluginDescriptionFile(input);
            var command = description.getCommands().get("mode");
            assertNotNull(command, "The player command must be registered in plugin.yml");
            assertEquals("usapo.mode.use", command.get("permission"));
            assertEquals("/mode", command.get("usage"));
            assertFalse(command.containsKey("aliases"));
            var permission = description.getPermissions().stream()
                    .filter(found -> found.getName().equals("usapo.mode.use"))
                    .findFirst().orElseThrow();
            assertEquals(PermissionDefault.TRUE, permission.getDefault());
            assertTrue(permission.getChildren().isEmpty(), "Must not grant gamemode or OP rights");
            assertEquals(PermissionDefault.OP, description.getPermissions().stream()
                    .filter(found -> found.getName().equals("usapo.eventbridge.control"))
                    .findFirst().orElseThrow().getDefault());
        }
    }
}
