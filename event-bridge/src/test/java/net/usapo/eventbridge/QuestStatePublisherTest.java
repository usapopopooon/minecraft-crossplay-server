package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;

final class QuestStatePublisherTest {
    @Test
    void publishesExactOwnerWorkerItemsCountsAndDeadlinesInStableOrder() {
        List<String> logs = new ArrayList<>();
        QuestStatePublisher publisher = new QuestStatePublisher(
                logs::add,
                Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC));
        UUID event = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID owner = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID worker = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID transition = UUID.fromString("44444444-4444-4444-8444-444444444444");
        ItemStack reward = item("diamond", 3, "ダイヤモンド");
        QuestListing quest = new QuestListing(
                17,
                event,
                owner,
                "Owner",
                "minecraft:ancient_debris",
                "古代の残骸",
                8,
                24,
                reward,
                QuestListing.Status.ACCEPTED,
                worker,
                "Worker",
                1_000,
                8_000,
                5_000,
                transition);

        publisher.publish(quest, "accepted");

        assertEquals(
                "USAPO_QUEST_STATE|1|" + transition + "|accepted|17|" + event + "|" + owner
                        + "|" + encoded("Owner") + "|" + worker + "|" + encoded("Worker")
                        + "|" + encoded("minecraft:ancient_debris") + "|" + encoded("古代の残骸")
                        + "|8|" + encoded("minecraft:diamond") + "|" + encoded("ダイヤモンド")
                        + "|3|24|accepted|8000|5000|1000|9000",
                logs.getFirst());
    }

    @Test
    void publishesEveryStoredEnchantmentOnAQuestReward() {
        List<String> logs = new ArrayList<>();
        ItemStack reward = enchantedBook();
        QuestListing quest = new QuestListing(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Owner",
                "minecraft:stone",
                "石",
                1,
                24,
                reward,
                QuestListing.Status.OPEN,
                null,
                null,
                1_000,
                8_000,
                0,
                UUID.randomUUID());

        new QuestStatePublisher(logs::add).publish(quest, "created");

        String[] fields = logs.getFirst().split("\\|");
        assertEquals(
                "エンチャントの本（水中採掘 / 虫特効 V / 修繕 / ダメージ増加 V / 耐久力 III）",
                decoded(fields[14]));
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(String key, int amount, String displayName) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(amount);
        when(item.effectiveName()).thenReturn(net.kyori.adventure.text.Component.text(displayName));
        return item;
    }

    @SuppressWarnings({"deprecation", "rawtypes", "unchecked"})
    private static ItemStack enchantedBook() {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft("enchanted_book"));
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        Map<Keyed, Integer> enchantments = Map.of(
                keyed("unbreaking"), 3,
                keyed("sharpness"), 5,
                keyed("mending"), 1,
                keyed("bane_of_arthropods"), 5,
                keyed("aqua_affinity"), 1);
        when(meta.getStoredEnchants()).thenReturn((Map) enchantments);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.effectiveName())
                .thenReturn(Component.translatable("item.minecraft.enchanted_book"));
        return item;
    }

    private static Keyed keyed(String key) {
        Keyed enchantment = mock(Keyed.class);
        when(enchantment.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return enchantment;
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
