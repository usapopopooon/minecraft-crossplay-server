package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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

    @Test
    void publishesCustomNameTogetherWithUnderlyingMaterial() {
        List<String> messages = new ArrayList<>();
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        MarketRequestPublisher publisher = new MarketRequestPublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                UUID::randomUUID);
        MarketListing listing = new MarketListing(
                17,
                eventId,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                ".Yuki1991",
                1_000,
                item(
                        material("diamond_axe"),
                        1,
                        Component.text("効率Ⅴ耐久力Ⅲ修繕付きの斧"),
                        axeEnchantments()),
                MarketListing.Status.ACTIVE,
                null,
                null);

        publisher.publishListing(listing);

        String event = messages.getFirst();
        String[] fields = event.substring(MarketRequestPublisher.LISTING_PREFIX.length())
                .split("\\|");
        assertEquals("minecraft:diamond_axe", decode(fields[4]));
        assertEquals("効率Ⅴ耐久力Ⅲ修繕付きの斧（ダイヤモンドの斧）", decode(fields[5]));
    }

    @Test
    void publishesPlayerAssignedNameWithEveryOrdinaryEnchantment() {
        List<String> messages = new ArrayList<>();
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        MarketRequestPublisher publisher = new MarketRequestPublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                UUID::randomUUID);
        MarketListing listing = new MarketListing(
                18,
                eventId,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                ".Yuki1991",
                1_000,
                item(
                        material("trident"),
                        1,
                        Component.text("海神の槍"),
                        tridentEnchantments()),
                MarketListing.Status.ACTIVE,
                null,
                null);

        publisher.publishListing(listing);

        String event = messages.getFirst();
        String[] fields = event.substring(MarketRequestPublisher.LISTING_PREFIX.length())
                .split("\\|");
        assertEquals("minecraft:trident", decode(fields[4]));
        assertEquals(
                "海神の槍（召雷 / 水生特効 V / 忠誠 III / 修繕 / 耐久力 III）",
                decode(fields[5]));
    }

    @Test
    void publishesStoredEnchantmentInEnchantedBookName() {
        List<String> messages = new ArrayList<>();
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        MarketRequestPublisher publisher = new MarketRequestPublisher(
                messages::add,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                UUID::randomUUID);
        Material material = material("enchanted_book");
        Keyed mending = () -> NamespacedKey.minecraft("mending");
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        stubStoredEnchantments(meta, Map.of(mending, 1));
        ItemStack book = item(
                material,
                1,
                Component.translatable("item.minecraft.enchanted_book"));
        when(book.getItemMeta()).thenReturn(meta);
        MarketListing listing = new MarketListing(
                19,
                eventId,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                ".Yuki1991",
                2_000,
                book,
                MarketListing.Status.ACTIVE,
                null,
                null);

        publisher.publishListing(listing);

        String event = messages.getFirst();
        String[] fields = event.substring(MarketRequestPublisher.LISTING_PREFIX.length())
                .split("\\|");
        assertEquals("エンチャントの本（修繕）", decode(fields[5]));
    }

    private static ItemStack item(Material material, int amount) {
        boolean block = material.isBlock();
        String materialKey = material.getKey().getKey();
        return item(
                material,
                amount,
                Component.translatable(
                        (block ? "block.minecraft." : "item.minecraft.") + materialKey));
    }

    private static ItemStack item(Material material, int amount, Component effectiveName) {
        return item(material, amount, effectiveName, Map.of());
    }

    private static ItemStack item(
            Material material,
            int amount,
            Component effectiveName,
            Map<Keyed, Integer> enchantments) {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.getItemMeta()).thenReturn(null);
        when(item.effectiveName()).thenReturn(effectiveName);
        stubEnchantments(item, enchantments);
        return item;
    }

    private static Map<Keyed, Integer> axeEnchantments() {
        return Map.of(
                keyed("efficiency"),
                5,
                keyed("unbreaking"),
                3,
                keyed("mending"),
                1);
    }

    private static Map<Keyed, Integer> tridentEnchantments() {
        return Map.of(
                keyed("unbreaking"), 3,
                keyed("loyalty"), 3,
                keyed("mending"), 1,
                keyed("impaling"), 5,
                keyed("channeling"), 1);
    }

    private static Keyed keyed(String key) {
        return () -> NamespacedKey.minecraft(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubEnchantments(ItemStack item, Map<Keyed, Integer> enchantments) {
        when(item.getEnchantments()).thenReturn((Map) enchantments);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubStoredEnchantments(
            EnchantmentStorageMeta meta, Map<Keyed, Integer> enchantments) {
        when(meta.getStoredEnchants()).thenReturn((Map) enchantments);
    }

    @SuppressWarnings("deprecation")
    private static Material material(String key) {
        Material material = mock(Material.class);
        when(material.isBlock()).thenReturn(key.equals("ancient_debris"));
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        when(material.translationKey()).thenReturn(
                (key.equals("ancient_debris") ? "block.minecraft." : "item.minecraft.") + key);
        return material;
    }

    private static String decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
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
