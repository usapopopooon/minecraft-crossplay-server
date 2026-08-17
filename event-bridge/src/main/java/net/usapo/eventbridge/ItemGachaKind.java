package net.usapo.eventbridge;

import java.util.Locale;
import java.util.Optional;

enum ItemGachaKind {
    NORMAL("normal", "通常", 100),
    PREMIUM("premium", "R以上確定", 1_000);

    private final String wireName;
    private final String label;
    private final int costXp;

    ItemGachaKind(String wireName, String label, int costXp) {
        this.wireName = wireName;
        this.label = label;
        this.costXp = costXp;
    }

    String wireName() {
        return wireName;
    }

    String label() {
        return label;
    }

    int costXp() {
        return costXp;
    }

    static Optional<ItemGachaKind> fromCommandArgument(String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "normal" -> Optional.of(NORMAL);
            case "rare", "premium" -> Optional.of(PREMIUM);
            default -> Optional.empty();
        };
    }
}
