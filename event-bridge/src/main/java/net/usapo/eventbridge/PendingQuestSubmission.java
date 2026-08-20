package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

record PendingQuestSubmission(UUID transitionId, long questId, ItemStack item) {
    PendingQuestSubmission {
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(item, "item");
        if (questId <= 0 || !QuestItems.isSimpleStack(item)) {
            throw new IllegalArgumentException("invalid pending quest submission");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("transition-id", transitionId.toString());
        yaml.set("quest-id", questId);
        yaml.set("item", item);
        return Base64.getEncoder()
                .encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    static PendingQuestSubmission decode(String encoded) {
        try {
            String source = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(source);
            ItemStack item = yaml.getItemStack("item");
            if (item == null) {
                throw new IllegalArgumentException("pending quest submission has no item");
            }
            return new PendingQuestSubmission(
                    UUID.fromString(yaml.getString("transition-id", "")),
                    yaml.getLong("quest-id"),
                    item);
        } catch (IllegalArgumentException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("invalid pending quest submission", error);
        }
    }
}
