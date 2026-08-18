package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

record PendingMarketEscrow(UUID eventId, int priceXp, ItemStack item) {
    PendingMarketEscrow {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(item, "item");
        if (priceXp <= 0 || item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("invalid pending market escrow");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("event-id", eventId.toString());
        yaml.set("price-xp", priceXp);
        yaml.set("item", item);
        return Base64.getEncoder()
                .encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    static PendingMarketEscrow decode(String encoded) {
        try {
            String source = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(source);
            ItemStack item = yaml.getItemStack("item");
            if (item == null) {
                throw new IllegalArgumentException("pending market escrow has no item");
            }
            return new PendingMarketEscrow(
                    UUID.fromString(yaml.getString("event-id", "")),
                    yaml.getInt("price-xp"),
                    item);
        } catch (IllegalArgumentException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("invalid pending market escrow", error);
        }
    }
}
