package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CyanGatePackTest {
    private static final UUID PACK = UUID.fromString("c73b7eef-646d-4554-92b7-deedd3335abf");
    private static final CyanGatePack.Settings SETTINGS = new CyanGatePack.Settings(PACK,
            "https://raw.githubusercontent.com/usapopopooon/minecraft-crossplay-server/main/portal-packs/dist/cyan-portal-java-v1.zip",
            "a".repeat(40), "b".repeat(64), "c".repeat(64));

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void status(CyanGatePack pack, Player player, UUID id, PlayerResourcePackStatusEvent.Status status) {
        PlayerResourcePackStatusEvent event = mock(PlayerResourcePackStatusEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getID()).thenReturn(id);
        when(event.getStatus()).thenReturn(status);
        pack.onStatus(event);
    }

    private void quit(CyanGatePack pack, Player player) {
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        pack.onQuit(event);
    }

    @Test void offersOptionalOwnPackOnlyOnceAndRequiresItsSuccess() {
        Player player = player(UUID.randomUUID());
        CyanGatePack pack = new CyanGatePack(SETTINGS, p -> false, false, Runnable::run);
        pack.offer(player);
        pack.offer(player);
        verify(player, times(1)).addResourcePack(eq(PACK), eq(SETTINGS.url()), any(byte[].class), anyString(), eq(false));
        assertFalse(pack.ready(player));
        status(pack, player, UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        assertFalse(pack.ready(player));
        status(pack, player, PACK, PlayerResourcePackStatusEvent.Status.ACCEPTED);
        assertFalse(pack.ready(player));
        status(pack, player, PACK, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        assertTrue(pack.ready(player));
        status(pack, player, PACK, PlayerResourcePackStatusEvent.Status.DECLINED);
        assertFalse(pack.ready(player));
        verify(player, never()).removeResourcePacks();
    }

    @Test void bedrockUsesOnlyVerifiedInstalledArtifactsAndReceivesNoJavaPack() {
        Player player = player(UUID.randomUUID());
        CyanGatePack good = new CyanGatePack(SETTINGS, p -> true, true, Runnable::run);
        CyanGatePack bad = new CyanGatePack(SETTINGS, p -> true, false, Runnable::run);
        good.offer(player);
        assertTrue(good.ready(player));
        assertFalse(bad.ready(player));
        verify(player, never()).addResourcePack(any(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test void missingMetadataFailsClosed() {
        Player player = player(UUID.randomUUID());
        CyanGatePack pack = new CyanGatePack(null, p -> true, true, Runnable::run);
        pack.offer(player);
        assertFalse(pack.ready(player));
    }

    @Test void delayedOfferAndStatusesCannotLeakAcrossReconnects() {
        UUID id = UUID.randomUUID();
        Player old = player(id);
        Player current = player(id);
        List<Runnable> scheduled = new ArrayList<>();
        CyanGatePack pack = new CyanGatePack(SETTINGS, p -> false, false, scheduled::add);
        pack.offer(old);
        pack.offer(current);
        scheduled.forEach(Runnable::run);
        verify(old, never()).addResourcePack(any(), anyString(), any(), anyString(), anyBoolean());
        verify(current).addResourcePack(eq(PACK), anyString(), any(), anyString(), eq(false));
        status(pack, old, PACK, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        assertFalse(pack.ready(current));
        status(pack, current, PACK, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        quit(pack, old);
        assertTrue(pack.ready(current));
        quit(pack, current);
        assertFalse(pack.ready(current));
    }

    @Test void verifiesExactBytesAndMissingFileFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pack");
        Files.writeString(file, "abc");
        assertTrue(CyanGatePack.matches(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertFalse(CyanGatePack.matches(file, "0".repeat(64)));
        assertFalse(CyanGatePack.matches(dir.resolve("missing"), "0".repeat(64)));
    }
}
