package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

record QuestDraft(
        String requestedItemId,
        String requestedItemName,
        ItemStack requestedItem,
        int requestedCount,
        int fulfillmentHours) {
    QuestDraft {
        Objects.requireNonNull(requestedItemId, "requestedItemId");
        Objects.requireNonNull(requestedItemName, "requestedItemName");
        if (!requestedItemId.startsWith("minecraft:")
                || requestedItemName.isBlank()
                || requestedCount <= 0
                || fulfillmentHours < 1
                || fulfillmentHours > 72) {
            throw new IllegalArgumentException("invalid quest draft");
        }
        if (requestedItem != null) {
            if (!QuestItems.isSupportedRequest(requestedItem)
                    || !requestedItemId.equals(requestedItem.getType().getKey().toString())) {
                throw new IllegalArgumentException("invalid requested quest item");
            }
            requestedItem = requestedItem.clone();
            requestedItem.setAmount(1);
        }
    }

    QuestDraft(
            String requestedItemId,
            String requestedItemName,
            int requestedCount,
            int fulfillmentHours) {
        this(requestedItemId, requestedItemName, null, requestedCount, fulfillmentHours);
    }

    @Override
    public ItemStack requestedItem() {
        return requestedItem == null ? null : requestedItem.clone();
    }

    String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("requested-item-id", requestedItemId);
        yaml.set("requested-item-name", requestedItemName);
        yaml.set("requested-item", requestedItem);
        yaml.set("requested-count", requestedCount);
        yaml.set("fulfillment-hours", fulfillmentHours);
        return Base64.getEncoder()
                .encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    static QuestDraft decode(String encoded) {
        try {
            String source = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(source);
            return new QuestDraft(
                    yaml.getString("requested-item-id", ""),
                    yaml.getString("requested-item-name", ""),
                    yaml.getItemStack("requested-item"),
                    yaml.getInt("requested-count"),
                    yaml.getInt("fulfillment-hours"));
        } catch (IllegalArgumentException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("invalid quest draft", error);
        }
    }
}
