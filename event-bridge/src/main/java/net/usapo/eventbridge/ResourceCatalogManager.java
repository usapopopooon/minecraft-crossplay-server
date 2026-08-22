package net.usapo.eventbridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

final class ResourceCatalogManager {
    private static final Pattern ITEM_ID = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final int MAX_ITEM_NAME_LENGTH = 64;
    private static final int MAX_ITEM_ID_LENGTH = 64;
    private static final int MAX_RESOURCE_COST_XP = 10_000_000;
    private static final int MAX_ENCODED_PAYLOAD_LENGTH = 8_000;

    private final ExchangeCatalog catalog;
    private final File file;
    private final Consumer<String> warningLogger;
    private final Predicate<String> giveableItem;

    ResourceCatalogManager(
            ExchangeCatalog catalog,
            File file,
            Consumer<String> warningLogger) {
        this(catalog, file, warningLogger, ResourceCatalogManager::isGiveableItem);
    }

    ResourceCatalogManager(
            ExchangeCatalog catalog,
            File file,
            Consumer<String> warningLogger,
            Predicate<String> giveableItem) {
        this.catalog = catalog;
        this.file = file;
        this.warningLogger = warningLogger;
        this.giveableItem = giveableItem;
    }

    void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            long revision = yaml.getLong("revision", -1);
            List<ExchangeSelection> resources = readPersistedPacks(yaml);
            validateCatalog(revision, resources);
            catalog.replaceResources(revision, resources);
        } catch (IllegalArgumentException error) {
            warningLogger.accept(
                    "Ignoring invalid resource catalog and using built-in defaults: "
                            + error.getMessage());
        }
    }

    synchronized SyncResult synchronize(long revision, String encodedPayload) {
        final List<ExchangeSelection> resources;
        try {
            resources = decodeAndValidate(revision, encodedPayload);
        } catch (IllegalArgumentException error) {
            return SyncResult.rejected(error.getMessage());
        }

        long currentRevision = catalog.revision();
        if (revision < currentRevision) {
            return SyncResult.rejected(
                    "stale catalog; current version is " + currentRevision);
        }
        if (revision == currentRevision) {
            if (catalog.resources().equals(resources)) {
                return SyncResult.current(revision, resources.size());
            }
            return SyncResult.rejected(
                    "catalog content differs from the current version " + currentRevision);
        }
        try {
            persist(revision, resources);
        } catch (IOException error) {
            warningLogger.accept("Could not persist resource catalog: " + error.getMessage());
            return SyncResult.rejected("could not save catalog");
        }
        catalog.replaceResources(revision, resources);
        return SyncResult.applied(revision, resources.size());
    }

    ValidationResult validatePack(String encodedPayload) {
        try {
            List<ExchangeSelection> resources = decodeAndValidate(0, encodedPayload);
            if (resources.size() != 1) {
                return ValidationResult.rejected("validation requires exactly one pack");
            }
            return ValidationResult.accepted();
        } catch (IllegalArgumentException error) {
            return ValidationResult.rejected(error.getMessage());
        }
    }

    private List<ExchangeSelection> decodeAndValidate(
            long revision, String encodedPayload) {
        if (encodedPayload.isEmpty()
                || encodedPayload.length() > MAX_ENCODED_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("catalog payload size is invalid");
        }
        final String decoded;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encodedPayload);
            decoded = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("catalog payload is not valid base64url", error);
        }
        if (decoded.isEmpty()) {
            throw new IllegalArgumentException("resource catalog must not be empty");
        }
        List<ExchangeSelection> resources = new ArrayList<>();
        String[] lines = decoded.split("\\n", -1);
        if (lines.length > ExchangeCatalog.MAX_RESOURCE_PACKS) {
            throw new IllegalArgumentException("resource catalog contains too many packs");
        }
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("catalog row must contain four fields");
            }
            final int count;
            final int costXp;
            try {
                count = Integer.parseInt(fields[2]);
                costXp = Integer.parseInt(fields[3]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("catalog count and cost must be integers", error);
            }
            resources.add(ExchangeCatalog.resource(
                    fields[0], fields[1].strip(), count, costXp));
        }
        validateCatalog(revision, resources);
        return List.copyOf(resources);
    }

    private static List<ExchangeSelection> readPersistedPacks(YamlConfiguration yaml) {
        List<ExchangeSelection> resources = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("packs")) {
            Object itemId = row.get("item-id");
            Object itemName = row.get("item-name");
            Object itemCount = row.get("item-count");
            Object costXp = row.get("cost-xp");
            if (!(itemId instanceof String id)
                    || !(itemName instanceof String name)
                    || !(itemCount instanceof Number count)
                    || !(costXp instanceof Number cost)) {
                throw new IllegalArgumentException("persisted catalog contains an invalid row");
            }
            resources.add(ExchangeCatalog.resource(
                    id,
                    name.strip(),
                    exactInteger(count, "item-count"),
                    exactInteger(cost, "cost-xp")));
        }
        return List.copyOf(resources);
    }

    private static int exactInteger(Number value, String field) {
        long parsed = value.longValue();
        if (parsed < Integer.MIN_VALUE
                || parsed > Integer.MAX_VALUE
                || value.doubleValue() != parsed) {
            throw new IllegalArgumentException("persisted " + field + " is out of range");
        }
        return (int) parsed;
    }

    private void validateCatalog(long revision, List<ExchangeSelection> resources) {
        if (revision < 0) {
            throw new IllegalArgumentException("catalog version must not be negative");
        }
        if (resources.isEmpty() || resources.size() > ExchangeCatalog.MAX_RESOURCE_PACKS) {
            throw new IllegalArgumentException(
                    "resource catalog must contain between 1 and "
                            + ExchangeCatalog.MAX_RESOURCE_PACKS + " packs");
        }
        Set<String> packs = new HashSet<>();
        Map<String, String> itemNames = new HashMap<>();
        for (ExchangeSelection selection : resources) {
            String itemId = selection.target();
            String itemName = selection.targetName();
            if (itemId.length() > MAX_ITEM_ID_LENGTH || !ITEM_ID.matcher(itemId).matches()) {
                throw new IllegalArgumentException("resource item id is invalid: " + itemId);
            }
            if (!giveableItem.test(itemId)) {
                throw new IllegalArgumentException("resource is not a giveable item: " + itemId);
            }
            if (itemName.isBlank()
                    || itemName.length() > MAX_ITEM_NAME_LENGTH
                    || itemName.chars().anyMatch(character -> Character.isISOControl(character))) {
                throw new IllegalArgumentException("resource display name is invalid");
            }
            if (selection.amount() < 1
                    || selection.amount() > 64
                    || selection.expectedCostXp() <= 0
                    || selection.expectedCostXp() > MAX_RESOURCE_COST_XP) {
                throw new IllegalArgumentException("resource amount or cost is invalid");
            }
            String identity = itemId + "\u0000" + selection.amount();
            if (!packs.add(identity)) {
                throw new IllegalArgumentException("resource catalog contains a duplicate pack");
            }
            String previousName = itemNames.putIfAbsent(itemId, itemName);
            if (previousName != null && !previousName.equals(itemName)) {
                throw new IllegalArgumentException(
                        "display names for the same resource must match");
            }
        }
    }

    private static boolean isGiveableItem(String itemId) {
        Material material = Material.matchMaterial(itemId);
        return material != null && material.isItem() && !material.isAir();
    }

    private void persist(long revision, List<ExchangeSelection> resources) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("revision", revision);
        List<Map<String, Object>> packs = resources.stream()
                .map(selection -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("item-id", selection.target());
                    row.put("item-name", selection.targetName());
                    row.put("item-count", selection.amount());
                    row.put("cost-xp", selection.expectedCostXp());
                    return row;
                })
                .toList();
        yaml.set("packs", packs);
        File temporary = new File(
                parent == null ? file.getAbsoluteFile().getParentFile() : parent,
                "." + file.getName() + ".tmp");
        yaml.save(temporary);
        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record SyncResult(Status status, long revision, int packCount, String error) {
        static SyncResult applied(long revision, int packCount) {
            return new SyncResult(Status.APPLIED, revision, packCount, "");
        }

        static SyncResult current(long revision, int packCount) {
            return new SyncResult(Status.CURRENT, revision, packCount, "");
        }

        static SyncResult rejected(String error) {
            return new SyncResult(Status.REJECTED, -1, 0, error);
        }

        enum Status {
            APPLIED,
            CURRENT,
            REJECTED
        }
    }

    record ValidationResult(boolean valid, String error) {
        static ValidationResult accepted() {
            return new ValidationResult(true, "");
        }

        static ValidationResult rejected(String error) {
            return new ValidationResult(false, error);
        }
    }
}
