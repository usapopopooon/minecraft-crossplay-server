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
                                8,
                                100,
                                8,
                                "サーバーXP 100 → 火薬 x8"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:gunpowder",
                                32,
                                360,
                                32,
                                "サーバーXP 360 → 火薬 x32"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:gunpowder",
                                64,
                                150,
                                64,
                                "サーバーXP 150 → 火薬 x64")),
                ExchangeCatalog.RESOURCES.stream()
                        .filter(selection -> selection.target().equals("minecraft:gunpowder"))
                        .toList());
    }

    @Test
    void resourceGroupsKeepEveryOfferInAReadableItemFirstOrder() {
        assertEquals(
                List.of("エメラルド", "火薬", "ダイヤモンド"),
                ExchangeCatalog.RESOURCE_GROUPS.stream()
                        .map(ExchangeCatalog.ResourceGroup::itemName)
                        .toList());
        assertEquals(
                ExchangeCatalog.RESOURCES,
                ExchangeCatalog.RESOURCE_GROUPS.stream()
                        .flatMap(group -> group.options().stream())
                        .toList());
        assertTrue(ExchangeCatalog.RESOURCE_GROUPS.stream()
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
        invoke(command, player, "balance");

        assertEquals(
                List.of(
                        new ExchangeSelection(
                                ExchangeKind.XP,
                                "minecraft:experience",
                                500,
                                100,
                                500,
                                "サーバーXP 100 → Minecraft 500 XP"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:diamond",
                                3,
                                2_160,
                                3,
                                "サーバーXP 2,160 → ダイヤモンド x3"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:emerald",
                                16,
                                360,
                                16,
                                "サーバーXP 360 → エメラルド x16"),
                        new ExchangeSelection(
                                ExchangeKind.RESOURCE,
                                "minecraft:gunpowder",
                                64,
                                150,
                                64,
                                "サーバーXP 150 → 火薬 x64"),
                        new ExchangeSelection(
                                ExchangeKind.EMERALD_DIAMOND,
                                "minecraft:diamond",
                                64,
                                0,
                                2,
                                "エメラルド x64 → ダイヤモンド x2"),
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
                List.of("1", "3", "8", "16", "32", "64"),
                command.onTabComplete(
                        player(new ArrayList<>()),
                        null,
                        "exchange",
                        new String[] {"resource", "diamond", ""}));
        assertEquals(
                List.of("8", "32", "64"),
                command.onTabComplete(
                        player(new ArrayList<>()),
                        null,
                        "exchange",
                        new String[] {"resource", "gunpowder", ""}));
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
    void buybackAllRejectsGuaranteedLimitOverrunAndMaxUsesTheSafeSingleTradeMaximum() {
        List<ExchangeSelection> requests = new ArrayList<>();
        ExchangeCommand command = new ExchangeCommand(
                sink(requests),
                (player, handler) -> false,
                () -> 10_000);
        Player player = playerWithPlainInventory(Material.SANDSTONE, 36);

        invoke(command, player, "buyback", "all");
        invoke(command, player, "buyback", "max");

        assertEquals(
                List.of(MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.find(Material.SANDSTONE).orElseThrow(), 1_920)),
                requests);
        org.mockito.Mockito.verify(player)
                .sendMessage(org.mockito.ArgumentMatchers.contains("1日の買取上限"));
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
                messages.stream().filter(message -> message.contains("前の資材買取を処理中"))
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
