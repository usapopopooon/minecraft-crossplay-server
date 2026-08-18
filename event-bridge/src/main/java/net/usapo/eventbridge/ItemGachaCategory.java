package net.usapo.eventbridge;

import java.util.Locale;
import java.util.Optional;

enum ItemGachaCategory {
    ALL("all", "おまかせ", ""),
    RESOURCES("resources", "資源・採掘", "resource"),
    ADVENTURE("adventure", "冒険", "adventure"),
    EQUIPMENT("equipment", "装備・強化", "equipment");

    private final String wireName;
    private final String label;
    private final String commandName;

    ItemGachaCategory(String wireName, String label, String commandName) {
        this.wireName = wireName;
        this.label = label;
        this.commandName = commandName;
    }

    String wireName() {
        return wireName;
    }

    String label() {
        return label;
    }

    String commandName() {
        return commandName;
    }

    static Optional<ItemGachaCategory> fromCommandArgument(String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "resource", "resources" -> Optional.of(RESOURCES);
            case "adventure" -> Optional.of(ADVENTURE);
            case "equipment", "gear" -> Optional.of(EQUIPMENT);
            default -> Optional.empty();
        };
    }
}
