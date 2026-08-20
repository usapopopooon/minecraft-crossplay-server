package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class FloodgateQuestFormGatewayTest {
    @Test
    void publicationConfirmationShowsTheExactEscrow() {
        QuestDraft draft = new QuestDraft("minecraft:ancient_debris", "古代の残骸", 8, 24);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft("diamond"));
        ItemStack reward = mock(ItemStack.class);
        when(reward.getType()).thenReturn(material);
        when(reward.getAmount()).thenReturn(3);
        when(reward.effectiveName()).thenReturn(null);

        assertEquals(
                "依頼品: 古代の残骸 x8\n受注後の期限: 24時間\n報酬: diamond x3\n\n"
                        + "この報酬スタックを預けて公開しますか？",
                FloodgateQuestFormGateway.publicationConfirmation(draft, reward));
    }
}
