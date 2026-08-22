package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

record PendingQuestReward(UUID eventId, QuestDraft draft, ItemStack reward) {
    PendingQuestReward {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(reward, "reward");
        if (!QuestItems.isSupportedReward(reward)) {
            throw new IllegalArgumentException("invalid quest reward");
        }
        reward = reward.clone();
    }

    @Override
    public ItemStack reward() {
        return reward.clone();
    }

    String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("event-id", eventId.toString());
        yaml.set("draft", draft.encode());
        yaml.set("reward", reward);
        return Base64.getEncoder()
                .encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    static PendingQuestReward decode(String encoded) {
        try {
            String source = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(source);
            ItemStack reward = yaml.getItemStack("reward");
            if (reward == null) {
                throw new IllegalArgumentException("pending quest reward has no item");
            }
            return new PendingQuestReward(
                    UUID.fromString(yaml.getString("event-id", "")),
                    QuestDraft.decode(yaml.getString("draft", "")),
                    reward);
        } catch (IllegalArgumentException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("invalid pending quest reward", error);
        }
    }
}
