package net.usapo.eventbridge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

final class ExchangeCatalog {
    static final List<ExchangeSelection> XP = List.of(
            xp(10, 50),
            xp(50, 250),
            xp(100, 500),
            xp(1_000, 5_000));
    static final List<ExchangeSelection> RESOURCES = List.of(
            resource("minecraft:emerald", "エメラルド", 4, 100),
            resource("minecraft:emerald", "エメラルド", 16, 360),
            resource("minecraft:emerald", "エメラルド", 32, 720),
            resource("minecraft:emerald", "エメラルド", 64, 1_440),
            resource("minecraft:gunpowder", "火薬", 8, 100),
            resource("minecraft:gunpowder", "火薬", 32, 360),
            resource("minecraft:gunpowder", "火薬", 64, 150),
            resource("minecraft:diamond", "ダイヤモンド", 1, 720),
            resource("minecraft:diamond", "ダイヤモンド", 3, 2_160),
            resource("minecraft:diamond", "ダイヤモンド", 8, 5_760),
            resource("minecraft:diamond", "ダイヤモンド", 16, 11_520),
            resource("minecraft:diamond", "ダイヤモンド", 32, 23_040),
            resource("minecraft:diamond", "ダイヤモンド", 64, 46_080));
    static final List<ResourceGroup> RESOURCE_GROUPS = List.of(
            resourceGroup("minecraft:emerald", "エメラルド"),
            resourceGroup("minecraft:gunpowder", "火薬"),
            resourceGroup("minecraft:diamond", "ダイヤモンド"));
    static final List<ExchangeSelection> EMERALD_DIAMOND = List.of(
            emeraldDiamond(32, 1),
            emeraldDiamond(64, 2));

    private ExchangeCatalog() {}

    static Optional<ExchangeSelection> findXp(int rewardXp) {
        return XP.stream().filter(selection -> selection.amount() == rewardXp).findFirst();
    }

    static Optional<ExchangeSelection> findResource(String itemName, int count) {
        String itemId = switch (itemName.toLowerCase(Locale.ROOT)) {
            case "diamond" -> "minecraft:diamond";
            case "emerald" -> "minecraft:emerald";
            case "gunpowder" -> "minecraft:gunpowder";
            default -> "";
        };
        return RESOURCES.stream()
                .filter(selection -> selection.target().equals(itemId)
                        && selection.amount() == count)
                .findFirst();
    }

    static Optional<ExchangeSelection> findEmeraldDiamond(int emeraldCount) {
        return EMERALD_DIAMOND.stream()
                .filter(selection -> selection.amount() == emeraldCount)
                .findFirst();
    }

    private static ExchangeSelection xp(int costXp, int rewardXp) {
        return new ExchangeSelection(
                ExchangeKind.XP,
                "minecraft:experience",
                rewardXp,
                costXp,
                rewardXp,
                "サーバーXP " + number(costXp) + " → Minecraft " + number(rewardXp) + " XP");
    }

    private static ExchangeSelection resource(
            String itemId,
            String itemName,
            int itemCount,
            int costXp) {
        return new ExchangeSelection(
                ExchangeKind.RESOURCE,
                itemId,
                itemCount,
                costXp,
                itemCount,
                "サーバーXP " + number(costXp) + " → " + itemName + " x" + itemCount);
    }

    private static ExchangeSelection emeraldDiamond(int emeraldCount, int diamondCount) {
        return new ExchangeSelection(
                ExchangeKind.EMERALD_DIAMOND,
                "minecraft:diamond",
                emeraldCount,
                0,
                diamondCount,
                "エメラルド x" + emeraldCount + " → ダイヤモンド x" + diamondCount);
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static ResourceGroup resourceGroup(String target, String itemName) {
        return new ResourceGroup(
                target,
                itemName,
                RESOURCES.stream()
                        .filter(selection -> selection.target().equals(target))
                        .toList());
    }

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
