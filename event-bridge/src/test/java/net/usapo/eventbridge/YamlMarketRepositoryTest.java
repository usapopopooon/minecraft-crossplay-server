package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

final class YamlMarketRepositoryTest {
    private static final UUID EVENT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SELLER =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RETURN =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    @TempDir
    File directory;

    @Test
    void offlineReturnAtomicallyCreatesPersistentMailboxClaim() throws IOException {
        File file = new File(directory, "market.yml");
        ItemStack item = item("diamond", 3);
        YamlMarketRepository repository = new YamlMarketRepository(file);
        MarketListing listing = repository.create(EVENT, SELLER, "Seller", 500, item);

        MarketMailboxReturn first = repository.returnToMailbox(listing.id(), RETURN, SELLER);
        MarketMailboxReturn duplicate = repository.returnToMailbox(listing.id(), RETURN, SELLER);

        assertEquals(MarketListing.Status.CANCELLED, first.listing().status());
        assertTrue(duplicate.duplicate());
        assertEquals(1, repository.pendingClaims(SELLER).size());
        assertEquals(3, repository.pendingClaims(SELLER).getFirst().item().getAmount());

        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.deserializeStack(org.mockito.ArgumentMatchers.anyMap())).thenReturn(item);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            repository = new YamlMarketRepository(file);
        }

        assertEquals(MarketListing.Status.CANCELLED, repository.find(listing.id()).orElseThrow().status());
        assertEquals(1, repository.pendingClaims(SELLER).size());
        MarketClaim claim = repository.pendingClaims(SELLER).getFirst();
        UUID delivery = UUID.fromString("44444444-4444-4444-8444-444444444444");
        repository.prepareClaim(claim.id(), SELLER, delivery);
        repository.completeClaim(claim.id(), SELLER, delivery);
        assertTrue(repository.pendingClaims(SELLER).isEmpty());
    }

    @Test
    void retryOfALegacyCompletedOnlineReturnDoesNotCreateADuplicateClaim() throws IOException {
        YamlMarketRepository repository =
                new YamlMarketRepository(new File(directory, "legacy-return.yml"));
        MarketListing listing = repository.create(EVENT, SELLER, "Seller", 500, item("diamond", 3));
        repository.prepareTransfer(listing.id(), RETURN, SELLER);
        repository.completeTransfer(listing.id(), RETURN, MarketListing.Status.CANCELLED);

        MarketMailboxReturn replay = repository.returnToMailbox(listing.id(), RETURN, SELLER);

        assertTrue(replay.duplicate());
        assertTrue(repository.pendingClaims(SELLER).isEmpty());
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(String key, int amount) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.hasItemMeta()).thenReturn(false);
        when(item.serialize()).thenReturn(java.util.Map.of(
                "schema_version", 1, "type", key, "amount", amount));
        return item;
    }
}
