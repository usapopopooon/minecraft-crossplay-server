package net.usapo.eventbridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

final class YamlMarketRepository implements MarketRepository {
    private final File file;
    private final Map<Long, MarketListing> listings = new LinkedHashMap<>();
    private long nextId = 1;

    YamlMarketRepository(File file) throws IOException {
        this.file = file;
        load();
    }

    @Override
    public synchronized MarketListing create(
            UUID eventId,
            UUID sellerId,
            String sellerName,
            int priceXp,
            ItemStack item)
            throws IOException {
        if (priceXp <= 0 || item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("invalid market listing");
        }
        MarketListing existing = listings.values().stream()
                .filter(listing -> listing.eventId().equals(eventId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.sellerId().equals(sellerId)
                    || !existing.sellerName().equals(sellerName)
                    || existing.priceXp() != priceXp
                    || !existing.item().equals(item)) {
                throw new IllegalStateException("market listing event conflict");
            }
            return existing;
        }
        long id = nextId;
        MarketListing listing = new MarketListing(
                id,
                eventId,
                sellerId,
                sellerName,
                priceXp,
                item,
                MarketListing.Status.ACTIVE,
                null,
                null);
        listings.put(id, listing);
        nextId++;
        try {
            save();
        } catch (IOException error) {
            listings.remove(id);
            nextId = id;
            throw error;
        }
        return listing;
    }

    @Override
    public synchronized Optional<MarketListing> find(long listingId) {
        return Optional.ofNullable(listings.get(listingId));
    }

    @Override
    public synchronized Optional<MarketListing> findByEventId(UUID eventId) {
        return listings.values().stream()
                .filter(listing -> listing.eventId().equals(eventId))
                .findFirst();
    }

    @Override
    public synchronized List<MarketListing> activeListings() {
        return listings.values().stream()
                .filter(listing -> listing.status() == MarketListing.Status.ACTIVE)
                .sorted(Comparator.comparingLong(MarketListing::id).reversed())
                .toList();
    }

    @Override
    public synchronized MarketListing prepareTransfer(
            long listingId, UUID transferId, UUID recipientId) throws IOException {
        MarketListing current = requireListing(listingId);
        if (current.status() == MarketListing.Status.DELIVERING
                && transferId.equals(current.transferId())
                && recipientId.equals(current.recipientId())) {
            return current;
        }
        if (current.status() != MarketListing.Status.ACTIVE) {
            throw new IllegalStateException("listing is no longer active");
        }
        MarketListing changed = new MarketListing(
                current.id(),
                current.eventId(),
                current.sellerId(),
                current.sellerName(),
                current.priceXp(),
                current.item(),
                MarketListing.Status.DELIVERING,
                transferId,
                recipientId);
        replaceAndSave(current, changed);
        return changed;
    }

    @Override
    public synchronized MarketListing completeTransfer(
            long listingId,
            UUID transferId,
            MarketListing.Status completedStatus)
            throws IOException {
        if (completedStatus != MarketListing.Status.SOLD
                && completedStatus != MarketListing.Status.CANCELLED) {
            throw new IllegalArgumentException("invalid completed status");
        }
        MarketListing current = requireListing(listingId);
        if (current.status() == completedStatus && transferId.equals(current.transferId())) {
            return current;
        }
        if (current.status() != MarketListing.Status.DELIVERING
                || !transferId.equals(current.transferId())) {
            throw new IllegalStateException("listing transfer does not match");
        }
        MarketListing changed = new MarketListing(
                current.id(),
                current.eventId(),
                current.sellerId(),
                current.sellerName(),
                current.priceXp(),
                current.item(),
                completedStatus,
                current.transferId(),
                current.recipientId());
        replaceAndSave(current, changed);
        return changed;
    }

    @Override
    public synchronized void abortTransfer(long listingId, UUID transferId) throws IOException {
        MarketListing current = requireListing(listingId);
        if (current.status() != MarketListing.Status.DELIVERING
                || !transferId.equals(current.transferId())) {
            return;
        }
        MarketListing active = new MarketListing(
                current.id(),
                current.eventId(),
                current.sellerId(),
                current.sellerName(),
                current.priceXp(),
                current.item(),
                MarketListing.Status.ACTIVE,
                null,
                null);
        replaceAndSave(current, active);
    }

    private MarketListing requireListing(long listingId) {
        MarketListing listing = listings.get(listingId);
        if (listing == null) {
            throw new IllegalArgumentException("unknown market listing");
        }
        return listing;
    }

    private void replaceAndSave(MarketListing previous, MarketListing changed) throws IOException {
        listings.put(changed.id(), changed);
        try {
            save();
        } catch (IOException error) {
            listings.put(previous.id(), previous);
            throw error;
        }
    }

    private void load() throws IOException {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yaml.getLong("next-id", 1));
        ConfigurationSection root = yaml.getConfigurationSection("listings");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String path = "listings." + key + ".";
                ItemStack item = yaml.getItemStack(path + "item");
                if (item == null || item.getType().isAir()) {
                    throw new IllegalArgumentException("missing item");
                }
                String transfer = yaml.getString(path + "transfer-id");
                String recipient = yaml.getString(path + "recipient-id");
                MarketListing listing = new MarketListing(
                        id,
                        UUID.fromString(yaml.getString(path + "event-id", "")),
                        UUID.fromString(yaml.getString(path + "seller-id", "")),
                        yaml.getString(path + "seller-name", "unknown"),
                        yaml.getInt(path + "price-xp"),
                        item,
                        MarketListing.Status.valueOf(yaml.getString(path + "status", "ACTIVE")),
                        transfer == null ? null : UUID.fromString(transfer),
                        recipient == null ? null : UUID.fromString(recipient));
                listings.put(id, listing);
                nextId = Math.max(nextId, id + 1);
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid market listing " + key, error);
            }
        }
    }

    private void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (MarketListing listing : new ArrayList<>(listings.values())) {
            String path = "listings." + listing.id() + ".";
            yaml.set(path + "event-id", listing.eventId().toString());
            yaml.set(path + "seller-id", listing.sellerId().toString());
            yaml.set(path + "seller-name", listing.sellerName());
            yaml.set(path + "price-xp", listing.priceXp());
            yaml.set(path + "item", listing.item());
            yaml.set(path + "status", listing.status().name());
            yaml.set(path + "transfer-id", optionalUuid(listing.transferId()));
            yaml.set(path + "recipient-id", optionalUuid(listing.recipientId()));
        }
        File temporary = new File(file.getParentFile(), "." + file.getName() + ".tmp");
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

    private static String optionalUuid(UUID value) {
        return value == null ? null : value.toString();
    }
}
