package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceCatalogCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void successfulRconResponseContainsTheVerifiedRevision() {
        ResourceCatalogManager manager = new ResourceCatalogManager(
                new ExchangeCatalog(),
                temporaryDirectory.resolve("resource-catalog.yml").toFile(),
                ignored -> {},
                ignored -> true);
        ResourceCatalogCommand resourceCatalog = new ResourceCatalogCommand(manager);
        CommandSender sender = mock(CommandSender.class);
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("minecraft:copper_ingot\t銅インゴット\t4\t75"
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(resourceCatalog.onCommand(
                sender,
                mock(Command.class),
                "usapo-event-bridge",
                new String[] {"resource-catalog-sync", "8", payload}));

        verify(sender).sendMessage(contains("synchronized: revision 8 (1 packs)"));
    }

    @Test
    void packPreflightReturnsAnUnambiguousSuccessResponse() {
        ResourceCatalogManager manager = new ResourceCatalogManager(
                new ExchangeCatalog(),
                temporaryDirectory.resolve("resource-catalog.yml").toFile(),
                ignored -> {},
                ignored -> true);
        ResourceCatalogCommand resourceCatalog = new ResourceCatalogCommand(manager);
        CommandSender sender = mock(CommandSender.class);
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("minecraft:copper_ingot\t銅インゴット\t4\t75"
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(resourceCatalog.onCommand(
                sender,
                mock(Command.class),
                "usapo-event-bridge",
                new String[] {"resource-pack-validate", payload}));

        verify(sender).sendMessage("Resource pack valid");
    }
}
