package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class MarketRequestPublisherTest {
    @Test
    void publishesListingSnapshotAndPurchaseWithConfirmedPrice() {
        List<String> messages = new ArrayList<>();
        UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID sellerId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        MarketRequestPublisher publisher = new MarketRequestPublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                () -> requestId);
        MarketListing listing = new MarketListing(
                17,
                requestId,
                sellerId,
                ".Yuki1991",
                3_000,
                item(material("ancient_debris"), 2),
                MarketListing.Status.ACTIVE,
                null,
                null);
        Player player = player("Steve", UUID.fromString("33333333-3333-4333-8333-333333333333"));

        publisher.publishListing(listing);
        publisher.publishRequest("buy", 17, 3_000, player);

        assertEquals(
                List.of(
                        "USAPO_MARKET_LISTING|1|" + requestId + "|17|" + sellerId
                                + "|Lll1a2kxOTkx|bWluZWNyYWZ0OmFuY2llbnRfZGVicmlz"
                                + "|5Y-k5Luj44Gu5q6L6aq4|2|3000|1787011200000",
                        "USAPO_MARKET_REQUEST|1|" + requestId
                                + "|buy|17|33333333-3333-4333-8333-333333333333"
                                + "|U3RldmU|3000|1787011200000"),
                messages);
    }

    private static ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.getItemMeta()).thenReturn(null);
        boolean block = material.isBlock();
        String materialKey = material.getKey().getKey();
        when(item.effectiveName()).thenReturn(Component.translatable(
                (block ? "block.minecraft." : "item.minecraft.") + materialKey));
        return item;
    }

    @SuppressWarnings("deprecation")
    private static Material material(String key) {
        Material material = mock(Material.class);
        when(material.isBlock()).thenReturn(key.equals("ancient_debris"));
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return material;
    }

    private static Player player(String name, UUID uniqueId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uniqueId;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }
}
