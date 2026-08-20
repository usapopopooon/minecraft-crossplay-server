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
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
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

    private static String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
