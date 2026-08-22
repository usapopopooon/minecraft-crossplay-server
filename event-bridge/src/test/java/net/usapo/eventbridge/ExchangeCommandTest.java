package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

final class ExchangeCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void gunpowderRatesMatchConfiguredOffers() {
        assertEquals(
                List.of(
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:gunpowder",
                                "火薬",
                                64,
                                150,
                                64,
                                "サーバーXP 150 → 火薬 x64")),
                new ExchangeCatalog().resources().stream()
                        .filter(selection -> selection.target().equals("minecraft:gunpowder"))
                        .toList());
    }

    @Test
    void resourceGroupsKeepEveryOfferInAReadableItemFirstOrder() {
        assertEquals(
                List.of("エメラルド", "火薬", "ダイヤモンド"),
                new ExchangeCatalog().resourceGroups().stream()
                        .map(ExchangeCatalog.ResourceGroup::itemName)
                        .toList());
        assertEquals(
                new ExchangeCatalog().resources(),
                new ExchangeCatalog().resourceGroups().stream()
                        .flatMap(group -> group.options().stream())
                        .toList());
        assertTrue(new ExchangeCatalog().resourceGroups().stream()
                .allMatch(group -> group.options().stream()
                        .allMatch(selection -> selection.target().equals(group.target()))));
    }

    @Test
    void directCommandsMapToExactAuthoritativeOffers() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicLong now = new AtomicLong(10_000);
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                now::get);
        Player player = player(new ArrayList<>());

        invoke(command, player, "xp", "500");
        now.addAndGet(2_000);
        invoke(command, player, "resource", "diamond", "3");
        now.addAndGet(2_000);
        invoke(command, player, "resource", "emerald", "16");
        now.addAndGet(2_000);
        invoke(command, player, "resource", "gunpowder", "64");
        now.addAndGet(2_000);
        invoke(command, player, "emerald-diamond", "64");
        now.addAndGet(2_000);
        invoke(command, player, "diamond-emerald", "4");
        now.addAndGet(2_000);
        invoke(command, player, "balance");

        assertEquals(
                List.of(
                        new ExchangeSelection(
                                ExchangeKind.XP,
                                "minecraft:experience",
                                "Minecraft XP",
                                500,
                                100,
                                500,
                                "サーバーXP 100 → Minecraft 500 XP"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:diamond",
                                "ダイヤモンド",
                                3,
                                750,
                                3,
                                "サーバーXP 750 → ダイヤモンド x3"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:emerald",
                                "エメラルド",
                                16,
                                250,
                                16,
                                "サーバーXP 250 → エメラルド x16"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:gunpowder",
                                "火薬",
                                64,
                                150,
                                64,
                                "サーバーXP 150 → 火薬 x64"),
                        new ExchangeSelection(
                                ExchangeKind.EMERALD_DIAMOND,
                                "minecraft:diamond",
                                "ダイヤモンド",
                                64,
                                0,
                                2,
                                "エメラルド x64 → ダイヤモンド x2"),
                        new ExchangeSelection(
                                ExchangeKind.DIAMOND_EMERALD,
                                "minecraft:emerald",
                                "エメラルド",
                                4,
                                0,
                                64,
                                "ダイヤモンド x4 → エメラルド x64"),
                        ExchangeSelection.balance()),
                requests);
    }

    @Test
    void bedrockFormSelectionUsesTheSameSubmissionPath() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>());

        assertTrue(command.onCommand(player, null, "exchange", new String[0]));
        selection.get().accept(ExchangeCatalog.XP.getFirst());

        assertEquals(List.of(ExchangeCatalog.XP.getFirst()), requests);
    }

    @Test
    void bedrockFormCannotSubmitAfterPlayerDisconnects() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>(), false);

        assertTrue(command.onCommand(player, null, "exchange", new String[0]));
        selection.get().accept(ExchangeCatalog.XP.getFirst());

        assertTrue(requests.isEmpty());
    }

    @Test
    void javaChestSelectionUsesTheSameSubmissionPathWhenBedrockFormDoesNotOpen() {
        List<ExchangeSelection> requests = new ArrayList<>();
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                () -> 10_000);
        Player player = player(new ArrayList<>());

        assertTrue(command.onCommand(player, null, "exchange", new String[0]));
        selection.get().accept(MaterialBuybackCatalog.selection(
                MaterialBuybackCatalog.find(Material.SANDSTONE).orElseThrow(), 256));

        assertEquals(
                List.of(MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.find(Material.SANDSTONE).orElseThrow(), 256)),
                requests);
    }

    @Test
    void invalidOrRapidRequestsNeverPublishUnexpectedExchange() {
        List<ExchangeSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);
        Player player = player(messages);

        invoke(command, player, "resource", "diamond", "2");
        invoke(command, player, "xp", "50");
        invoke(command, player, "xp", "5000");

        assertEquals(List.of(ExchangeCatalog.XP.getFirst()), requests);
        assertTrue(messages.stream().anyMatch(message -> message.contains("処理中")));
    }

    @Test
    void consoleCannotExchangeAndTabCompletionOnlyOffersValidValues() {
        List<ExchangeSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);

        assertTrue(command.onCommand(
                sender(messages), null, "exchange", new String[] {"xp", "50"}));

        assertTrue(requests.isEmpty());
        assertTrue(messages.getFirst().contains("プレイヤー"));
        assertEquals(
                List.of("1", "3", "8", "16"),
                command.onTabComplete(
                        player(new ArrayList<>()),
                        null,
                        "exchange",
                        new String[] {"resource", "diamond", ""}));
        assertEquals(
                List.of("64"),
                command.onTabComplete(
                        player(new ArrayList<>()),
                        null,
                        "exchange",
                        new String[] {"resource", "gunpowder", ""}));
    }

    @Test
    void directCommandAndTabCompletionUseTheCurrentDynamicCatalog() {
        List<ExchangeSelection> requests = new ArrayList<>();
        ExchangeCatalog catalog = new ExchangeCatalog(
                6,
                List.of(
                        ExchangeCatalog.resource("minecraft:copper_ingot", "銅インゴット", 4, 75),
                        ExchangeCatalog.resource("minecraft:copper_ingot", "銅インゴット", 16, 250)));
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                (player, handler) -> false,
                catalog,
                new MaterialBuybackPendingRegistry(),
                () -> 10_000);
        Player player = player(new ArrayList<>());

        invoke(command, player, "resource", "copper_ingot", "16");

        assertEquals(
                List.of(catalog.findResource("copper_ingot", 16).orElseThrow()),
                requests);
        assertEquals(
                List.of("copper_ingot"),
                command.onTabComplete(
                        player,
                        null,
                        "exchange",
                        new String[] {"resource", "c"}));
        assertEquals(
                List.of("4", "16"),
                command.onTabComplete(
                        player,
                        null,
                        "exchange",
                        new String[] {"resource", "copper_ingot", ""}));
    }

    @Test
    void buybackCommandInfersTheJapaneseServerItemFromTheMainHand() {
        List<ExchangeSelection> requests = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack dirt = mock(ItemStack.class);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(dirt.getAmount()).thenReturn(64);
        when(dirt.hasItemMeta()).thenReturn(false);
        when(inventory.getItemInMainHand()).thenReturn(dirt);
        when(inventory.getStorageContents())
                .thenReturn(new ItemStack[] {dirt, dirt, dirt, dirt});

        invoke(command, player, "buyback", "4");

        assertEquals(
                List.of(MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.find(Material.DIRT).orElseThrow(), 256)),
                requests);
    }

    @Test
    void buybackAllUsesEveryPlainStackWhenItFitsTheRaisedDailyLimit() {
        List<ExchangeSelection> requests = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);
        Player player = playerWithPlainInventory(Material.SANDSTONE, 36);

        invoke(command, player, "buyback", "all");

        assertEquals(
                List.of(MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.find(Material.SANDSTONE).orElseThrow(), 2_304)),
                requests);
    }

    @Test
    void emeraldBuybackRejectsEightStacksBeforePublishingAndMaxSelectsSix() {
        List<ExchangeSelection> requests = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);
        Player player = playerWithPlainInventory(Material.EMERALD, 8);

        invoke(command, player, "buyback", "8");
        invoke(command, player, "buyback", "max");

        assertEquals(
                List.of(MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.EMERALD_RATE, 384)),
                requests);
        org.mockito.Mockito.verify(player)
                .sendMessage(org.mockito.ArgumentMatchers.contains("1日の売却上限"));
    }

    @Test
    void pendingBuybackBlocksRetriesUntilTheMatchingRequestIsReleased() {
        List<ExchangeSelection> requests = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        AtomicLong now = new AtomicLong(10_000);
        AtomicReference<Consumer<ExchangeSelection>> selection = new AtomicReference<>();
        MaterialBuybackPendingRegistry pending = new MaterialBuybackPendingRegistry();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                (player, handler) -> {
                    selection.set(handler);
                    return true;
                },
                pending,
                now::get);
        Player player = player(messages);
        ExchangeSelection buyback = MaterialBuybackCatalog.selection(
                MaterialBuybackCatalog.find(Material.SAND).orElseThrow(), 64);

        invoke(command, player);
        selection.get().accept(buyback);
        now.addAndGet(10_000);
        selection.get().accept(buyback);
        assertEquals(
                MaterialBuybackPendingRegistry.ReleaseStatus.REQUEST_MISMATCH,
                pending.release(PLAYER_ID, UUID.randomUUID()));
        selection.get().accept(buyback);
        assertEquals(
                MaterialBuybackPendingRegistry.ReleaseStatus.RELEASED,
                pending.release(PLAYER_ID, REQUEST_ID));
        selection.get().accept(buyback);

        assertEquals(List.of(buyback, buyback), requests);
        assertEquals(
                2,
                messages.stream().filter(message -> message.contains("前の資源売却を処理中"))
                        .count());
    }

    private static void invoke(ExchangeCommand command, Player player, String... arguments) {
        assertTrue(command.onCommand(player, null, "exchange", arguments));
    }

    private static ExchangeRequestSink sink(List<ExchangeSelection> requests) {
        return (selection, player) -> {
            requests.add(selection);
            return REQUEST_ID;
        };
    }

    private static Player player(List<String> messages) {
        return player(messages, true);
    }

    private static Player player(List<String> messages, boolean online) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "Steve";
                    case "getUniqueId" -> PLAYER_ID;
                    case "isOnline" -> online;
                    case "sendMessage" -> {
                        if (arguments != null
                                && arguments.length == 1
                                && arguments[0] instanceof String message) {
                            messages.add(message);
                        }
                        yield null;
                    }
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static Player playerWithPlainInventory(Material material, int stacks) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(64);
        when(item.hasItemMeta()).thenReturn(false);
        when(inventory.getItemInMainHand()).thenReturn(item);
        ItemStack[] contents = new ItemStack[stacks];
        java.util.Arrays.fill(contents, item);
        when(inventory.getStorageContents()).thenReturn(contents);
        return player;
    }

    private static CommandSender sender(List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof String message) {
                        messages.add(message);
                    }
                    return EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }
}
