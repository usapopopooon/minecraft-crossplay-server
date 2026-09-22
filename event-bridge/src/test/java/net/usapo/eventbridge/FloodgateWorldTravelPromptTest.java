package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.impl.FormImpl;
import org.geysermc.cumulus.response.ModalFormResponse;
import org.geysermc.cumulus.response.result.ClosedFormResponseResult;
import org.geysermc.cumulus.response.result.InvalidFormResponseResult;
import org.geysermc.cumulus.response.result.ValidFormResponseResult;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.Test;

final class FloodgateWorldTravelPromptTest {
    @Test
    void confirmationExplainsInventoryChangeAndRunsOnceOnMainThread() throws Exception {
        Harness h = harness(true);
        assertTrue(h.open());
        ModalForm form = h.form();
        assertEquals("ワールド移動の確認", form.title());
        assertEquals("移動する", form.button1());
        assertEquals("やめる", form.button2());
        for (String expected : List.of("world_2", "ホットバー", "装備", "オフハンド",
                "エンダーチェスト", "保存", "戻ります", "XP", "変わりません")) {
            assertTrue(form.content().contains(expected), expected);
        }

        choose(form, true);
        choose(form, true);
        implementation(form).callResultHandler(ClosedFormResponseResult.instance());

        assertEquals(0, h.confirmed.get());
        assertEquals(0, h.cancelled.get());
        assertEquals(1, h.scheduled.size());
        h.scheduled.getFirst().run();
        assertEquals(1, h.confirmed.get());
        assertEquals(0, h.cancelled.get());
        verify(h.player, never()).getInventory();
    }

    @Test
    void secondButtonCancelsWithoutConfirming() throws Exception {
        Harness h = harness(true);
        h.open();
        choose(h.form(), false);
        h.scheduled.getFirst().run();
        assertEquals(0, h.confirmed.get());
        assertEquals(1, h.cancelled.get());
    }

    @Test
    void closedFormCancelsOnceAndCannotLaterConfirm() throws Exception {
        Harness h = harness(true);
        h.open();
        implementation(h.form()).callResultHandler(ClosedFormResponseResult.instance());
        choose(h.form(), true);
        assertEquals(1, h.scheduled.size());
        h.scheduled.getFirst().run();
        assertEquals(0, h.confirmed.get());
        assertEquals(1, h.cancelled.get());
    }

    @Test
    void invalidFormResponseCancelsOnMainThread() throws Exception {
        Harness h = harness(true);
        h.open();
        implementation(h.form()).callResultHandler(InvalidFormResponseResult.of(0, "invalid"));
        assertEquals(0, h.cancelled.get());
        assertEquals(1, h.scheduled.size());
        h.scheduled.getFirst().run();
        assertEquals(1, h.cancelled.get());
        assertEquals(0, h.confirmed.get());
    }

    @Test
    void failedSendReturnsFalseAndDiscardsLateResponses() throws Exception {
        Harness h = harness(false);
        assertFalse(h.open());
        choose(h.form(), true);
        assertTrue(h.scheduled.isEmpty());
    }

    private static Harness harness(boolean sent) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        List<Runnable> scheduled = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return null;
        });
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        FloodgateApi floodgate = mock(FloodgateApi.class);
        when(floodgate.isFloodgatePlayer(id)).thenReturn(true);
        List<Form> forms = new ArrayList<>();
        when(floodgate.sendForm(eq(id), any(Form.class))).thenAnswer(invocation -> {
            forms.add(invocation.getArgument(1));
            return sent;
        });
        return new Harness(new FloodgateWorldTravelPrompt(plugin, floodgate), player, forms,
                scheduled, new AtomicInteger(), new AtomicInteger());
    }

    private static void choose(ModalForm form, boolean first) throws Exception {
        ModalFormResponse response = mock(ModalFormResponse.class);
        when(response.clickedFirst()).thenReturn(first);
        implementation(form).callResultHandler(ValidFormResponseResult.of(response));
    }

    @SuppressWarnings("unchecked")
    private static FormImpl<ModalFormResponse> implementation(ModalForm form) {
        return (FormImpl<ModalFormResponse>) assertInstanceOf(FormImpl.class, form);
    }

    private record Harness(
            FloodgateWorldTravelPrompt prompt,
            Player player,
            List<Form> forms,
            List<Runnable> scheduled,
            AtomicInteger confirmed,
            AtomicInteger cancelled) {
        boolean open() {
            return prompt.open(player, "world_2", confirmed::incrementAndGet, cancelled::incrementAndGet);
        }

        ModalForm form() {
            return assertInstanceOf(ModalForm.class, forms.getFirst());
        }
    }
}
