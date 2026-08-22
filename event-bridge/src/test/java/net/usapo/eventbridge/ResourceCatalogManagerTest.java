package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceCatalogManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void synchronizedCatalogIsAppliedImmediatelyAndRestoredAfterRestart() {
        File file = temporaryDirectory.resolve("resource-catalog.yml").toFile();
        ExchangeCatalog catalog = new ExchangeCatalog();
        List<String> warnings = new ArrayList<>();
        ResourceCatalogManager manager =
                new ResourceCatalogManager(catalog, file, warnings::add, ignored -> true);

        ResourceCatalogManager.SyncResult result = manager.synchronize(
                4,
                payload(
                        "minecraft:copper_ingot\t銅インゴット\t4\t75",
                        "minecraft:copper_ingot\t銅インゴット\t16\t250"));

        assertEquals(ResourceCatalogManager.SyncResult.Status.APPLIED, result.status());
        assertEquals(4, catalog.revision());
        assertEquals(
                250,
                catalog.findResource("copper_ingot", 16)
                        .orElseThrow()
                        .expectedCostXp());
        assertTrue(file.isFile());
        assertTrue(warnings.isEmpty());

        ExchangeCatalog restored = new ExchangeCatalog();
        new ResourceCatalogManager(restored, file, warnings::add, ignored -> true).load();

        assertEquals(4, restored.revision());
        assertEquals(catalog.resources(), restored.resources());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidStaleOrConflictingCatalogNeverChangesTheActiveSnapshot() {
        ExchangeCatalog catalog = new ExchangeCatalog();
        ResourceCatalogManager manager = new ResourceCatalogManager(
                catalog,
                temporaryDirectory.resolve("resource-catalog.yml").toFile(),
                ignored -> {},
                itemId -> !itemId.equals("minecraft:not_a_real_item"));
        String accepted = payload("minecraft:emerald\tエメラルド\t4\t100");
        assertEquals(
                ResourceCatalogManager.SyncResult.Status.APPLIED,
                manager.synchronize(3, accepted).status());
        List<ExchangeSelection> active = catalog.resources();

        assertEquals(
                ResourceCatalogManager.SyncResult.Status.CURRENT,
                manager.synchronize(3, accepted).status());
        assertEquals(
                ResourceCatalogManager.SyncResult.Status.REJECTED,
                manager.synchronize(
                                3, payload("minecraft:emerald\tエメラルド\t4\t999"))
                        .status());
        assertEquals(
                ResourceCatalogManager.SyncResult.Status.REJECTED,
                manager.synchronize(
                                2, payload("minecraft:diamond\tダイヤモンド\t1\t1"))
                        .status());
        assertEquals(
                ResourceCatalogManager.SyncResult.Status.REJECTED,
                manager.synchronize(
                                4, payload("minecraft:not_a_real_item\t謎\t1\t1"))
                        .status());
        assertEquals(
                ResourceCatalogManager.SyncResult.Status.REJECTED,
                manager.synchronize(
                                4,
                                payload(
                                        "minecraft:emerald\tエメラルド\t4\t100",
                                        "minecraft:emerald\t別名\t16\t200"))
                        .status());

        assertEquals(3, catalog.revision());
        assertEquals(active, catalog.resources());
        assertFalse(catalog.findResource("diamond", 1).isPresent());
    }

    @Test
    void corruptPersistedCatalogFallsBackToBuiltInCatalog() throws Exception {
        File file = temporaryDirectory.resolve("resource-catalog.yml").toFile();
        java.nio.file.Files.writeString(
                file.toPath(),
                "revision: 9\npacks:\n  - item-id: minecraft:not_a_real_item\n"
                        + "    item-name: 謎\n    item-count: 1\n    cost-xp: 1\n");
        ExchangeCatalog catalog = new ExchangeCatalog();
        List<String> warnings = new ArrayList<>();

        new ResourceCatalogManager(
                        catalog,
                        file,
                        warnings::add,
                        itemId -> !itemId.equals("minecraft:not_a_real_item"))
                .load();

        assertEquals(0, catalog.revision());
        assertEquals(ExchangeCatalog.DEFAULT_RESOURCES, catalog.resources());
        assertEquals(1, warnings.size());
    }

    @Test
    void packValidationUsesTheServerItemRegistryWithoutChangingTheCatalog() {
        ExchangeCatalog catalog = new ExchangeCatalog();
        ResourceCatalogManager manager = new ResourceCatalogManager(
                catalog,
                temporaryDirectory.resolve("resource-catalog.yml").toFile(),
                ignored -> {},
                itemId -> itemId.equals("minecraft:copper_ingot"));

        assertTrue(manager
                .validatePack(payload("minecraft:copper_ingot\t銅インゴット\t16\t240"))
                .valid());
        assertFalse(manager
                .validatePack(payload("minecraft:not_a_real_item\t謎\t1\t1"))
                .valid());
        assertEquals(0, catalog.revision());
        assertEquals(ExchangeCatalog.DEFAULT_RESOURCES, catalog.resources());
    }

    private static String payload(String... lines) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
