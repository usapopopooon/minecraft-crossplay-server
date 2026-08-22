package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

final class YamlQuestRepositoryTest {
    private static final UUID OWNER =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKER =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCEPT =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID COMPLETE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    @TempDir
    File directory;

    @Test
    void completionAtomicallyCreatesBothClaimsAndSurvivesReload() throws IOException {
        File file = new File(directory, "quest.yml");
        YamlQuestRepository repository = new YamlQuestRepository(file);
        QuestListing created = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:ancient_debris",
                "古代の残骸",
                8,
                24,
                item("diamond", 3),
                1_000);

        QuestTransition accepted = repository.accept(created.id(), ACCEPT, WORKER, "Worker", 2_000);
        QuestTransition completed = repository.complete(
                created.id(),
                COMPLETE,
                WORKER,
                item("ancient_debris", 8),
                3_000);

        assertEquals(QuestListing.Status.ACCEPTED, accepted.quest().status());
        assertEquals(QuestListing.Status.COMPLETED, completed.quest().status());
        assertFalse(completed.duplicate());
        assertEquals("completed", repository.pendingStatePublications()
                .getFirst()
                .transitionKind());
        assertEquals(
                COMPLETE, repository.pendingCompletionBroadcasts().getFirst().lastTransitionId());
        assertClaim(repository.pendingClaims(OWNER), "ancient_debris", 8);
        assertClaim(repository.pendingClaims(WORKER), "diamond", 3);

        repository.markStatePublished(created.id(), COMPLETE);
        repository.markCompletionBroadcasted(created.id(), COMPLETE);
        assertTrue(repository.pendingStatePublications().isEmpty());
        assertTrue(repository.pendingCompletionBroadcasts().isEmpty());

        QuestTransition oldAcceptRetry =
                repository.accept(created.id(), ACCEPT, WORKER, "Worker", 4_000);
        assertTrue(oldAcceptRetry.duplicate());
        assertEquals(QuestListing.Status.COMPLETED, oldAcceptRetry.quest().status());
        assertClaim(repository.pendingClaims(OWNER), "ancient_debris", 8);
        assertClaim(repository.pendingClaims(WORKER), "diamond", 3);
        assertTrue(file.isFile());
    }

    @Test
    void acceptedQuestCanBeInvalidatedAndReturnsReward() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "invalidated.yml"));
        QuestListing created = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                32,
                24,
                item("diamond", 3),
                1_000);
        repository.accept(created.id(), ACCEPT, WORKER, "Worker", 2_000);
        UUID invalidated = UUID.fromString("88888888-8888-4888-8888-888888888888");

        QuestTransition result = repository.invalidate(created.id(), invalidated, OWNER);

        assertEquals(QuestListing.Status.CANCELLED, result.quest().status());
        assertEquals(null, result.quest().workerId());
        assertEquals("invalidated", repository.pendingStatePublications()
                .getFirst()
                .transitionKind());
        assertClaim(repository.pendingClaims(OWNER), "diamond", 3);
        assertEquals(
                "依頼者の参加登録が解除されたため、クエスト #1 は終了し、受注も解除されました。",
                repository.pendingNotices(WORKER).getFirst().message());
    }

    @Test
    void releasedAssignmentReopensAndNotifiesTheOwner() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "abandoned.yml"));
        QuestListing created = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                32,
                24,
                item("diamond", 3),
                1_000);
        repository.accept(created.id(), ACCEPT, WORKER, "Worker", 2_000);
        UUID abandoned = UUID.fromString("99999999-9999-4999-8999-999999999999");

        QuestTransition result = repository.abandon(created.id(), abandoned, WORKER, 3_000);

        assertEquals(QuestListing.Status.OPEN, result.quest().status());
        assertEquals(
                "クエスト #1 は受注解除により再募集しました。",
                repository.pendingNotices(OWNER).getFirst().message());
    }

    @Test
    void completionRejectsADifferentEnchantedBook() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "enchanted-book.yml"));
        ItemStack mending = enchantedBook("mending", 1);
        ItemStack unbreaking = enchantedBook("unbreaking", 3);
        when(mending.isSimilar(mending)).thenReturn(true);
        when(mending.isSimilar(unbreaking)).thenReturn(false);
        QuestListing created = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:enchanted_book",
                "エンチャントの本（修繕）",
                mending,
                1,
                24,
                item("diamond", 3),
                1_000);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.deserializeStack(org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(invocation -> {
                    java.util.Map<String, Object> serialized = invocation.getArgument(0);
                    return "enchanted_book".equals(serialized.get("type"))
                            ? mending
                            : item("diamond", 3);
                });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            repository = new YamlQuestRepository(new File(directory, "enchanted-book.yml"));
        }
        repository.accept(created.id(), ACCEPT, WORKER, "Worker", 2_000);
        YamlQuestRepository recovered = repository;

        assertThrows(
                IllegalStateException.class,
                () -> recovered.complete(created.id(), COMPLETE, WORKER, unbreaking, 3_000));
        assertTrue(repository.pendingClaims(OWNER).isEmpty());
        assertTrue(repository.pendingClaims(WORKER).isEmpty());
    }

    @Test
    void openExpiryReturnsRewardAndAcceptedExpiryReopens() throws IOException {
        File file = new File(directory, "expiry.yml");
        YamlQuestRepository repository = new YamlQuestRepository(file);
        QuestListing open = repository.create(
                EVENT,
                OWNER,
                "Owner",
                "minecraft:stone",
                "石",
                32,
                1,
                item("emerald", 4),
                1_000);

        List<QuestTransition> openExpiry = repository.expire(open.openExpiresAtMillis());

        assertEquals(1, openExpiry.size());
        assertEquals(QuestListing.Status.CANCELLED, openExpiry.getFirst().quest().status());
        assertClaim(repository.pendingClaims(OWNER), "emerald", 4);
        assertEquals(
                List.of("クエスト #1 は募集期限切れで終了しました。報酬は受取箱へ戻しました。"),
                repository.pendingNotices(OWNER).stream().map(QuestNotice::message).toList());

        QuestListing accepted = repository.create(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                OWNER,
                "Owner",
                "minecraft:cobblestone",
                "丸石",
                16,
                1,
                item("gold_ingot", 2),
                2_000);
        QuestTransition assignment = repository.accept(
                accepted.id(),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                WORKER,
                "Worker",
                3_000);

        List<QuestTransition> acceptedExpiry =
                repository.expire(assignment.quest().acceptedDeadlineMillis());

        assertEquals(1, acceptedExpiry.size());
        assertEquals(QuestListing.Status.OPEN, acceptedExpiry.getFirst().quest().status());
        assertEquals(null, acceptedExpiry.getFirst().quest().workerId());
        assertEquals(
                "クエスト #2 の納品期限が切れたため、受注を解除して再募集しました。",
                repository.pendingNotices(WORKER).getFirst().message());

        UnsafeValues unsafe = mock(UnsafeValues.class);
        ItemStack deserialized = item("stone", 1);
        when(unsafe.deserializeStack(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(deserialized);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            repository = new YamlQuestRepository(file);
        }

        assertEquals(1, repository.pendingNotices(WORKER).size());
        QuestNotice workerNotice = repository.pendingNotices(WORKER).getFirst();
        repository.acknowledgeNotice(workerNotice.id(), WORKER);
        assertTrue(repository.pendingNotices(WORKER).isEmpty());
    }

    @Test
    void systemQuestConsumesSubmissionAndOnlyCreatesTheWorkersRewardClaim() throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "system-completion.yml"));
        ItemStack requested = item("netherite_sword", 1);
        when(requested.getType().isItem()).thenReturn(true);
        when(requested.isSimilar(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        ItemStack submitted = item("netherite_sword", 1);
        when(submitted.getType().isItem()).thenReturn(true);
        ItemStack wrongType = item("stone", 1);
        when(wrongType.getType().isItem()).thenReturn(true);
        QuestListing created = repository.create(
                EVENT,
                QuestIssuer.SYSTEM_ID,
                QuestIssuer.SYSTEM_NAME,
                "minecraft:netherite_sword",
                "ネザライトの剣",
                requested,
                1,
                24,
                item("diamond", 3),
                1_000);
        repository.accept(created.id(), ACCEPT, WORKER, "Worker", 2_000);

        assertThrows(
                IllegalStateException.class,
                () -> repository.complete(created.id(), COMPLETE, WORKER, wrongType, 3_000));
        repository.complete(created.id(), COMPLETE, WORKER, submitted, 3_000);

        assertTrue(repository.pendingClaims(QuestIssuer.SYSTEM_ID).isEmpty());
        assertClaim(repository.pendingClaims(WORKER), "diamond", 3);
    }

    @Test
    void cancellingOrExpiringASystemQuestDoesNotMintARefundOrPlayerNotice()
            throws IOException {
        YamlQuestRepository repository =
                new YamlQuestRepository(new File(directory, "system-cancel.yml"));
        ItemStack requested = item("stone", 1);
        when(requested.getType().isItem()).thenReturn(true);
        QuestListing cancelled = repository.create(
                EVENT,
                QuestIssuer.SYSTEM_ID,
                QuestIssuer.SYSTEM_NAME,
                "minecraft:stone",
                "石",
                requested,
                32,
                24,
                item("diamond", 3),
                1_000);
        repository.cancel(cancelled.id(), UUID.randomUUID(), QuestIssuer.SYSTEM_ID);

        ItemStack expiringRequest = item("dirt", 1);
        when(expiringRequest.getType().isItem()).thenReturn(true);
        QuestListing expiring = repository.create(
                UUID.randomUUID(),
                QuestIssuer.SYSTEM_ID,
                QuestIssuer.SYSTEM_NAME,
                "minecraft:dirt",
                "土",
                expiringRequest,
                32,
                24,
                item("emerald", 4),
                2_000);
        repository.expire(expiring.openExpiresAtMillis());

        assertTrue(repository.pendingClaims(QuestIssuer.SYSTEM_ID).isEmpty());
        assertTrue(repository.pendingNotices(QuestIssuer.SYSTEM_ID).isEmpty());
    }

    private static void assertClaim(List<QuestClaim> claims, String key, int amount) {
        assertEquals(1, claims.size());
        assertEquals(key, claims.getFirst().item().getType().getKey().getKey());
        assertEquals(amount, claims.getFirst().item().getAmount());
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
        when(item.serialize())
                .thenReturn(java.util.Map.of(
                        "schema_version", 1, "type", key, "amount", amount));
        return item;
    }

    @SuppressWarnings({"deprecation", "rawtypes", "unchecked"})
    private static ItemStack enchantedBook(String enchantmentKey, int level) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft("enchanted_book"));
        org.bukkit.Keyed enchantment = mock(org.bukkit.Keyed.class);
        when(enchantment.getKey()).thenReturn(NamespacedKey.minecraft(enchantmentKey));
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        when(meta.getStoredEnchants()).thenReturn((java.util.Map) java.util.Map.of(enchantment, level));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.serialize())
                .thenReturn(java.util.Map.of(
                        "schema_version", 1,
                        "type", "enchanted_book",
                        "amount", 1,
                        "enchantment", enchantmentKey,
                        "level", level));
        return item;
    }
}
