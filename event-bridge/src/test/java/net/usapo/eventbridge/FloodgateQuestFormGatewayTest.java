package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.geysermc.cumulus.component.SliderComponent;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.impl.FormImpl;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.cumulus.response.ModalFormResponse;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.response.result.ValidFormResponseResult;
import org.geysermc.floodgate.api.FloodgateApi;
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

    @Test
    void creationFormUsesControllerFriendlySlidersAndMapsTheirValues() throws Exception {
        AtomicReference<QuestFormAction> selected = new AtomicReference<>();
        CustomForm form = FloodgateQuestFormGateway.creationForm(
                "古代の残骸", 64, selected::set);

        SliderComponent count = assertInstanceOf(SliderComponent.class, form.content().get(0));
        SliderComponent hours = assertInstanceOf(SliderComponent.class, form.content().get(1));
        assertEquals("古代の残骸 の依頼数（1スタック以内）", count.text());
        assertEquals(1, count.minValue());
        assertEquals(64, count.maxValue());
        assertEquals(1, count.step());
        assertEquals(32, count.defaultValue());
        assertEquals(1, hours.minValue());
        assertEquals(72, hours.maxValue());
        assertEquals(24, hours.defaultValue());

        CustomFormResponse response = mock(CustomFormResponse.class);
        when(response.asSlider(0)).thenReturn(32f);
        when(response.asSlider(1)).thenReturn(48f);
        @SuppressWarnings("unchecked")
        FormImpl<CustomFormResponse> implementation =
                (FormImpl<CustomFormResponse>) assertInstanceOf(FormImpl.class, form);
        implementation.callResultHandler(ValidFormResponseResult.of(response));

        assertEquals(
                new QuestFormAction(QuestFormAction.Kind.CREATE, 0, 32, 48),
                selected.get());
    }

    @Test
    void creationFormKeepsTheDefaultWithinSmallStackLimits() {
        CustomForm form = FloodgateQuestFormGateway.creationForm(
                "雪玉", 16, ignored -> {});

        SliderComponent count = assertInstanceOf(SliderComponent.class, form.content().get(0));
        assertEquals(16, count.maxValue());
        assertEquals(16, count.defaultValue());
    }

    @Test
    void creationFormForAnEnchantedBookOmitsTheCountSlider() throws Exception {
        AtomicReference<QuestFormAction> selected = new AtomicReference<>();
        CustomForm form = FloodgateQuestFormGateway.creationForm(
                "エンチャントの本（修繕）", 1, selected::set);

        assertEquals(2, form.content().size());
        SliderComponent hours = assertInstanceOf(SliderComponent.class, form.content().get(1));
        assertEquals("受注後の納品期限（時間）", hours.text());
        CustomFormResponse response = mock(CustomFormResponse.class);
        when(response.asSlider(1)).thenReturn(48f);
        formImplementation(form).callResultHandler(ValidFormResponseResult.of(response));

        assertEquals(
                new QuestFormAction(QuestFormAction.Kind.CREATE, 0, 1, 48),
                selected.get());
    }

    @Test
    void creationFormRejectsAnInvalidStackLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FloodgateQuestFormGateway.creationForm("無効", 0, ignored -> {}));
    }

    @Test
    void rootShowsClaimCountAndOmitsAnEmptyClaimAction() {
        QuestRepository repository = mock(QuestRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.pendingClaims(playerId)).thenReturn(List.of());
        Harness harness = harness(repository, playerId, null, null);

        harness.gateway().open(harness.player(), ignored -> {});

        SimpleForm empty = assertInstanceOf(SimpleForm.class, harness.forms().getFirst());
        assertTrue(empty.content().contains("受取箱: 0件"));
        assertTrue(empty.buttons().stream()
                .noneMatch(button -> button.text().startsWith("受取箱を受け取る")));

        when(repository.pendingClaims(playerId))
                .thenReturn(List.of(mock(QuestClaim.class), mock(QuestClaim.class)));
        harness.gateway().open(harness.player(), ignored -> {});

        SimpleForm pending = assertInstanceOf(SimpleForm.class, harness.forms().getLast());
        assertTrue(pending.content().contains("受取箱: 2件"));
        assertTrue(pending.buttons().stream()
                .anyMatch(button -> button.text().equals("受取箱を受け取る（2件）")));
    }

    @Test
    void createButtonUsesSlidersAndPassesTheSelectedValues() throws Exception {
        QuestRepository repository = mock(QuestRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.pendingClaims(playerId)).thenReturn(List.of());
        ItemStack held = simpleItem("砂", 64);
        Harness harness = harness(repository, playerId, held, null);
        List<QuestFormAction> actions = new ArrayList<>();

        harness.gateway().open(harness.player(), actions::add);
        click(assertInstanceOf(SimpleForm.class, harness.forms().getFirst()), 1);

        CustomForm create = assertInstanceOf(CustomForm.class, harness.forms().getLast());
        CustomFormResponse response = mock(CustomFormResponse.class);
        when(response.asSlider(0)).thenReturn(16f);
        when(response.asSlider(1)).thenReturn(12f);
        formImplementation(create).callResultHandler(ValidFormResponseResult.of(response));

        assertEquals(
                List.of(new QuestFormAction(QuestFormAction.Kind.CREATE, 0, 16, 12)),
                actions);
    }

    @Test
    void discardButtonRequiresConfirmation() throws Exception {
        QuestRepository repository = mock(QuestRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.pendingClaims(playerId)).thenReturn(List.of());
        QuestDraft draft = new QuestDraft("minecraft:sand", "砂", 32, 24);
        Harness harness = harness(repository, playerId, null, draft.encode());
        List<QuestFormAction> actions = new ArrayList<>();

        harness.gateway().open(harness.player(), actions::add);
        click(assertInstanceOf(SimpleForm.class, harness.forms().getFirst()), 3);

        ModalForm confirmation = assertInstanceOf(ModalForm.class, harness.forms().getLast());
        assertEquals("下書き破棄の確認", confirmation.title());
        assertTrue(confirmation.content().contains("依頼品: 砂 x32"));
        assertTrue(actions.isEmpty());
        choose(confirmation, true);

        assertEquals(
                List.of(new QuestFormAction(QuestFormAction.Kind.DISCARD, 0, 0, 0)),
                actions);
    }

    @Test
    void ownQuestOnTheSecondPageCannotBeAcceptedAndOpensCancellationConfirmation()
            throws Exception {
        QuestRepository repository = mock(QuestRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.pendingClaims(playerId)).thenReturn(List.of());
        List<QuestListing> quests = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            quests.add(quest(id, UUID.randomUUID()));
        }
        QuestListing own = quest(11, playerId);
        quests.add(own);
        when(repository.openQuests()).thenReturn(quests);
        when(repository.find(11)).thenReturn(java.util.Optional.of(own));
        Harness harness = harness(repository, playerId, null, null);
        List<QuestFormAction> actions = new ArrayList<>();

        harness.gateway().open(harness.player(), actions::add);
        click(assertInstanceOf(SimpleForm.class, harness.forms().get(0)), 0);

        SimpleForm first = assertInstanceOf(SimpleForm.class, harness.forms().get(1));
        assertEquals("次のページ", first.buttons().get(10).text());
        click(first, 10);

        SimpleForm second = assertInstanceOf(SimpleForm.class, harness.forms().get(2));
        assertTrue(second.buttons().get(0).text().startsWith("【自分の依頼】 #11 "));
        click(second, 0);

        SimpleForm ownDetails = assertInstanceOf(SimpleForm.class, harness.forms().get(3));
        assertEquals("取り消し内容を確認", ownDetails.buttons().get(0).text());
        assertTrue(actions.isEmpty());
        click(ownDetails, 0);

        ModalForm confirmation = assertInstanceOf(ModalForm.class, harness.forms().get(4));
        assertEquals("依頼を取り消す", confirmation.title());
        assertTrue(actions.isEmpty());
        choose(confirmation, true);

        assertEquals(
                List.of(new QuestFormAction(QuestFormAction.Kind.CANCEL, 11, 0, 0)),
                actions);
    }

    private static QuestListing quest(long id, UUID ownerId) {
        QuestListing quest = mock(QuestListing.class);
        when(quest.id()).thenReturn(id);
        when(quest.ownerId()).thenReturn(ownerId);
        when(quest.ownerName()).thenReturn("依頼者" + id);
        when(quest.requestedLabel()).thenReturn("砂 x32");
        when(quest.rewardLabel()).thenReturn("エメラルド x1");
        when(quest.fulfillmentHours()).thenReturn(24);
        when(quest.status()).thenReturn(QuestListing.Status.OPEN);
        return quest;
    }

    private static ItemStack simpleItem(String name, int maximum) {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.getMaxStackSize()).thenReturn(maximum);
        when(item.hasItemMeta()).thenReturn(false);
        when(item.effectiveName()).thenReturn(Component.text(name));
        when(item.getEnchantments()).thenReturn(Map.of());
        when(item.clone()).thenReturn(item);
        return item;
    }

    private static Harness harness(
            QuestRepository repository,
            UUID playerId,
            ItemStack held,
            String encodedDraft) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        });
        FloodgateApi floodgate = mock(FloodgateApi.class);
        when(floodgate.isFloodgatePlayer(playerId)).thenReturn(true);
        List<Form> forms = new ArrayList<>();
        when(floodgate.sendForm(eq(playerId), any(Form.class))).thenAnswer(invocation -> {
            forms.add(invocation.getArgument(1));
            return true;
        });
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        NamespacedKey draftKey = NamespacedKey.minecraft("quest_draft_test");
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.get(draftKey, PersistentDataType.STRING)).thenReturn(encodedDraft);
        if (held != null) {
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(held);
        }
        return new Harness(
                new FloodgateQuestFormGateway(plugin, repository, floodgate, draftKey),
                player,
                forms);
    }

    private static void click(SimpleForm form, int button) throws Exception {
        SimpleFormResponse response = mock(SimpleFormResponse.class);
        when(response.clickedButtonId()).thenReturn(button);
        formImplementation(form).callResultHandler(ValidFormResponseResult.of(response));
    }

    private static void choose(ModalForm form, boolean first) throws Exception {
        ModalFormResponse response = mock(ModalFormResponse.class);
        when(response.clickedFirst()).thenReturn(first);
        formImplementation(form).callResultHandler(ValidFormResponseResult.of(response));
    }

    @SuppressWarnings("unchecked")
    private static <R extends org.geysermc.cumulus.response.FormResponse>
            FormImpl<R> formImplementation(Form form) {
        return (FormImpl<R>) assertInstanceOf(FormImpl.class, form);
    }

    private record Harness(
            FloodgateQuestFormGateway gateway, Player player, List<Form> forms) {}
}
