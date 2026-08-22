package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class ExchangeCatalog {
    static final int MAX_RESOURCE_PACKS = 25;
    static final List<ExchangeSelection> XP = List.of(
            xp(10, 50),
            xp(50, 250),
            xp(100, 500),
            xp(1_000, 5_000));
    static final List<ExchangeSelection> DEFAULT_RESOURCES = List.of(
            resource("minecraft:emerald", "エメラルド", 4, 75),
            resource("minecraft:emerald", "エメラルド", 16, 250),
            resource("minecraft:emerald", "エメラルド", 32, 500),
            resource("minecraft:emerald", "エメラルド", 64, 1_000),
            resource("minecraft:gunpowder", "火薬", 64, 150),
            resource("minecraft:diamond", "ダイヤモンド", 1, 250),
            resource("minecraft:diamond", "ダイヤモンド", 3, 750),
            resource("minecraft:diamond", "ダイヤモンド", 8, 2_000),
            resource("minecraft:diamond", "ダイヤモンド", 16, 4_000));
    static final List<ExchangeSelection> EMERALD_DIAMOND = List.of(
            emeraldDiamond(32, 1),
            emeraldDiamond(64, 2));
    static final List<ExchangeSelection> DIAMOND_EMERALD = List.of(
            diamondEmerald(1, 16),
            diamondEmerald(4, 64));
    static final List<ExchangeSelection> VALUABLE_CONVERSIONS = java.util.stream.Stream.concat(
                    EMERALD_DIAMOND.stream(), DIAMOND_EMERALD.stream())
            .toList();

    private volatile Snapshot snapshot;

    ExchangeCatalog() {
        this(0, DEFAULT_RESOURCES);
    }

    ExchangeCatalog(long revision, List<ExchangeSelection> resources) {
        snapshot = snapshot(revision, resources);
    }

    long revision() {
        return snapshot.revision();
    }

    List<ExchangeSelection> resources() {
        return snapshot.resources();
    }

    List<ResourceGroup> resourceGroups() {
        return snapshot.resourceGroups();
    }

    synchronized void replaceResources(long revision, List<ExchangeSelection> resources) {
        snapshot = snapshot(revision, resources);
    }

    static Optional<ExchangeSelection> findXp(int rewardXp) {
        return XP.stream().filter(selection -> selection.amount() == rewardXp).findFirst();
    }

    Optional<ExchangeSelection> findResource(String itemName, int count) {
        String normalized = itemName.toLowerCase(Locale.ROOT);
        String itemId = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        return snapshot.resources().stream()
                .filter(selection -> selection.target().equals(itemId)
                        && selection.amount() == count)
                .findFirst();
    }

    static Optional<ExchangeSelection> findEmeraldDiamond(int emeraldCount) {
        return EMERALD_DIAMOND.stream()
                .filter(selection -> selection.amount() == emeraldCount)
                .findFirst();
    }

    static Optional<ExchangeSelection> findDiamondEmerald(int diamondCount) {
        return DIAMOND_EMERALD.stream()
                .filter(selection -> selection.amount() == diamondCount)
                .findFirst();
    }

    private static Snapshot snapshot(long revision, List<ExchangeSelection> resources) {
        if (revision < 0 || resources.isEmpty() || resources.size() > MAX_RESOURCE_PACKS) {
            throw new IllegalArgumentException("invalid resource catalog size or revision");
        }
        List<ExchangeSelection> immutableResources = List.copyOf(resources);
        Map<String, List<ExchangeSelection>> grouped = new LinkedHashMap<>();
        Map<String, String> itemNames = new LinkedHashMap<>();
        for (ExchangeSelection selection : immutableResources) {
            if (selection.kind() != ExchangeKind.RESOURCE) {
                throw new IllegalArgumentException("resource catalog contains another kind");
            }
            String itemName = selection.targetName();
            String previousName = itemNames.putIfAbsent(selection.target(), itemName);
            if (previousName != null && !previousName.equals(itemName)) {
                throw new IllegalArgumentException("resource display names must be consistent");
            }
            grouped.computeIfAbsent(selection.target(), ignored -> new ArrayList<>())
                    .add(selection);
        }
        List<ResourceGroup> groups = grouped.entrySet().stream()
                .map(entry -> new ResourceGroup(
                        entry.getKey(), itemNames.get(entry.getKey()), entry.getValue()))
                .toList();
        return new Snapshot(revision, immutableResources, groups);
    }

    private static ExchangeSelection xp(int costXp, int rewardXp) {
        return new ExchangeSelection(
                ExchangeKind.XP,
                "minecraft:experience",
                "Minecraft XP",
                rewardXp,
                costXp,
                rewardXp,
                "サーバーXP " + number(costXp) + " → Minecraft " + number(rewardXp) + " XP");
    }

    static ExchangeSelection resource(
            String itemId,
            String itemName,
            int itemCount,
            int costXp) {
        return new ExchangeSelection(
                ExchangeKind.RESOURCE,
                itemId,
                itemName,
                itemCount,
                costXp,
                itemCount,
                "サーバーXP " + number(costXp) + " → " + itemName + " x" + itemCount);
    }

    private static ExchangeSelection emeraldDiamond(int emeraldCount, int diamondCount) {
        return new ExchangeSelection(
                ExchangeKind.EMERALD_DIAMOND,
                "minecraft:diamond",
                "ダイヤモンド",
                emeraldCount,
                0,
                diamondCount,
                "エメラルド x" + emeraldCount + " → ダイヤモンド x" + diamondCount);
    }

    private static ExchangeSelection diamondEmerald(int diamondCount, int emeraldCount) {
        return new ExchangeSelection(
                ExchangeKind.DIAMOND_EMERALD,
                "minecraft:emerald",
                "エメラルド",
                diamondCount,
                0,
                emeraldCount,
                "ダイヤモンド x" + diamondCount + " → エメラルド x" + emeraldCount);
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private record Snapshot(
            long revision,
            List<ExchangeSelection> resources,
            List<ResourceGroup> resourceGroups) {}

    record ResourceGroup(
            String target, String itemName, List<ExchangeSelection> options) {
        ResourceGroup {
            options = List.copyOf(options);
            if (target.isBlank() || itemName.isBlank() || options.isEmpty()) {
                throw new IllegalArgumentException("invalid resource group");
            }
            if (options.stream().anyMatch(selection -> selection.kind() != ExchangeKind.RESOURCE
                    || !selection.target().equals(target))) {
                throw new IllegalArgumentException("resource group contains an unrelated option");
            }
        }

        String amountsLabel() {
            return options.stream()
                    .map(selection -> "x" + selection.amount())
                    .collect(Collectors.joining(" / "));
        }
    }
}
