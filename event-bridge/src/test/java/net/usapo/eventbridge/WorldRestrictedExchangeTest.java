package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorldRestrictedExchangeTest {
    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final NamespacedKey HISTORY = new NamespacedKey("usapo", "restriction_test");

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void exchangeAndGachaRejectCommandsAndMenuOpenInEveryRestrictedDimension(String world) {
        Player player = player(world);
        ExchangeRequestSink exchanges = mock(ExchangeRequestSink.class);
        BedrockExchangeFormGateway exchangeForms = mock(BedrockExchangeFormGateway.class);
        JavaExchangeMenuGateway exchangeMenus = mock(JavaExchangeMenuGateway.class);
        ExchangeCommand exchange = new ExchangeCommand(exchanges, exchangeForms, exchangeMenus);
        ItemGachaRequestSink gachaRequests = mock(ItemGachaRequestSink.class);
        BedrockGachaFormGateway gachaForms = mock(BedrockGachaFormGateway.class);
        ItemGachaCommand gacha = new ItemGachaCommand(gachaRequests, gachaForms);

        for (String[] arguments : new String[][] {
                {}, {"xp", "500"}, {"resource", "diamond", "3"},
                {"emerald-diamond", "32"}, {"diamond-emerald", "1"},
                {"buyback", "max"}, {"balance"}}) {
            assertTrue(exchange.onCommand(player, null, "exchange", arguments));
        }
        assertTrue(gacha.onCommand(player, null, "gacha", new String[0]));
        assertTrue(gacha.onCommand(player, null, "gacha", new String[] {"normal"}));
        assertTrue(gacha.onCommand(player, null, "gacha", new String[] {"equipment", "rare"}));

        verifyNoInteractions(exchanges, exchangeForms, exchangeMenus, gachaRequests, gachaForms);
        verify(player, never()).getInventory();
        verify(player, org.mockito.Mockito.times(10)).sendMessage(WorldEconomyPolicy.MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void exchangeJavaAndBedrockCallbacksRecheckWorldBeforePublishing(String restrictedWorld) {
        for (boolean javaMenu : new boolean[] {false, true}) {
            Player player = player("world");
            ExchangeRequestSink requests = mock(ExchangeRequestSink.class);
            AtomicReference<Consumer<ExchangeSelection>> handler = new AtomicReference<>();
            ExchangeCommand command = new ExchangeCommand(
                    requests,
                    (found, callback) -> {
                        if (javaMenu) return false;
                        handler.set(callback);
                        return true;
                    },
                    (found, callback) -> {
                        handler.set(callback);
                        return true;
                    });
            command.onCommand(player, null, "exchange", new String[0]);
            setWorld(player, restrictedWorld);
            handler.get().accept(ExchangeCatalog.XP.getFirst());

            verifyNoInteractions(requests);
            verify(player).sendMessage(WorldEconomyPolicy.MESSAGE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void gachaCallbackRechecksWorldBeforePublishing(String restrictedWorld) {
        Player player = player("world");
        ItemGachaRequestSink requests = mock(ItemGachaRequestSink.class);
        AtomicReference<Consumer<ItemGachaSelection>> handler = new AtomicReference<>();
        ItemGachaCommand command = new ItemGachaCommand(requests, (found, callback) -> {
            handler.set(callback);
            return true;
        });
        command.onCommand(player, null, "gacha", new String[0]);
        setWorld(player, restrictedWorld);
        handler.get().accept(new ItemGachaSelection(ItemGachaCategory.ALL, ItemGachaKind.NORMAL));

        verifyNoInteractions(requests);
        verify(player).sendMessage(WorldEconomyPolicy.MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"world", "world_nether", "world_the_end"})
    void worldOneGroupStillPublishesExchangeAndGachaRequests(String world) {
        Player player = player(world);
        ExchangeRequestSink exchanges = mock(ExchangeRequestSink.class);
        ExchangeCommand exchange = new ExchangeCommand(exchanges, (found, callback) -> false);
        ItemGachaRequestSink gachaRequests = mock(ItemGachaRequestSink.class);
        ItemGachaCommand gacha = new ItemGachaCommand(gachaRequests, (found, callback) -> false);

        exchange.onCommand(player, null, "exchange", new String[] {"xp", "500"});
        gacha.onCommand(player, null, "gacha", new String[] {"normal"});

        verify(exchanges).publish(ExchangeCatalog.findXp(500).orElseThrow(), player);
        verify(gachaRequests).publish(ItemGachaCategory.ALL, ItemGachaKind.NORMAL, player);
        verify(player, never()).sendMessage(WorldEconomyPolicy.MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void restrictedNewRconConversionsReportStatusWithoutReadingOrMutatingInventory(String world) {
        Player player = player(world);
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        CommandSender sender = mock(CommandSender.class);
        EmeraldDiamondCommand.SuccessNotifier emeraldNotifier =
                mock(EmeraldDiamondCommand.SuccessNotifier.class);
        DiamondEmeraldCommand.SuccessNotifier diamondNotifier =
                mock(DiamondEmeraldCommand.SuccessNotifier.class);
        EmeraldDiamondCommand emerald = new EmeraldDiamondCommand(
                ignored -> player,
                (found, request, count) -> exchange.exchange(
                        new EmeraldDiamondExchange.BukkitPlayerState(found, HISTORY), request, count),
                emeraldNotifier);
        DiamondEmeraldCommand diamond = new DiamondEmeraldCommand(
                ignored -> player,
                (found, request, count) -> exchange.exchangeDiamonds(
                        new EmeraldDiamondExchange.BukkitPlayerState(found, HISTORY), request, count),
                diamondNotifier);

        emerald.onCommand(sender, null, "usapo-event-bridge", new String[] {
                "emerald-diamond-v2", PLAYER_ID.toString(), "32", REQUEST_ID.toString()});
        diamond.onCommand(sender, null, "usapo-event-bridge", new String[] {
                "diamond-emerald-v1", PLAYER_ID.toString(), "1", REQUEST_ID.toString()});

        verify(sender).sendMessage("USAPO_EMERALD_EXCHANGE_RESULT|2|" + REQUEST_ID
                + "|world_restricted|32|1|new");
        verify(sender).sendMessage("USAPO_DIAMOND_EXCHANGE_RESULT|1|" + REQUEST_ID
                + "|world_restricted|1|16|new");
        verifyNoInteractions(emeraldNotifier, diamondNotifier);
        verifyNoMutation(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void completedConversionsRemainReconciliableAfterMovingToRestrictedWorld(String world) {
        Player player = player(world);
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        var state = new EmeraldDiamondExchange.BukkitPlayerState(player, HISTORY);
        when(player.getPersistentDataContainer().get(HISTORY, PersistentDataType.STRING))
                .thenReturn(REQUEST_ID + "|32");
        var emeraldResult = exchange.exchange(state, REQUEST_ID, 32);
        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, emeraldResult.status());
        assertTrue(emeraldResult.duplicate());

        when(player.getPersistentDataContainer().get(HISTORY, PersistentDataType.STRING))
                .thenReturn(REQUEST_ID + "|1");
        var diamondResult = exchange.exchangeDiamonds(state, REQUEST_ID, 1);
        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, diamondResult.status());
        assertTrue(diamondResult.duplicate());
        verifyNoMutation(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "world_2_nether", "world_2_the_end"})
    void buybackBlocksNewRconRequestsButReconcilesCompletedRequests(String world) {
        Player player = player(world);
        CommandSender sender = mock(CommandSender.class);
        MaterialBuybackCommand command = new MaterialBuybackCommand(
                ignored -> player, HISTORY, new MaterialBuybackExchange(),
                new MaterialBuybackPendingRegistry(), Logger.getLogger("WorldRestrictedExchangeTest"));
        String[] args = {"material-buyback", PLAYER_ID.toString(), "minecraft:sand", "64",
                REQUEST_ID.toString()};
        command.onCommand(sender, null, "usapo-event-bridge", args);
        verify(sender).sendMessage(MaterialBuybackCommand.RESULT_PREFIX + REQUEST_ID
                + "|world_restricted|minecraft:sand|64|new");

        when(player.getPersistentDataContainer().get(HISTORY, PersistentDataType.STRING))
                .thenReturn(REQUEST_ID + "|minecraft:sand|64");
        command.onCommand(sender, null, "usapo-event-bridge", args);
        verify(sender).sendMessage(MaterialBuybackCommand.RESULT_PREFIX + REQUEST_ID
                + "|completed|minecraft:sand|64|duplicate");
        verifyNoMutation(player);
    }

    private static Player player(String world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        setWorld(player, world);
        return player;
    }

    private static void setWorld(Player player, String name) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(name));
        when(player.getWorld()).thenReturn(world);
    }

    private static void verifyNoMutation(Player player) {
        verify(player, never()).getInventory();
        verify(player, never()).saveData();
        verify(player.getPersistentDataContainer(), never())
                .set(eq(HISTORY), eq(PersistentDataType.STRING), any(String.class));
        verify(player.getPersistentDataContainer(), never()).remove(HISTORY);
    }
}
