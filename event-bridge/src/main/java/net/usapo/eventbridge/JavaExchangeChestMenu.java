package net.usapo.eventbridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

final class JavaExchangeChestMenu implements JavaExchangeMenuGateway, Listener {
    private static final int MENU_SIZE = 27;

    private final JavaPlugin plugin;

    JavaExchangeChestMenu(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean open(Player player, Consumer<ExchangeSelection> selectionHandler) {
        if (!player.isOnline()) {
            return true;
        }
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, "Minecraft 交換所", actions);
        add(inventory, actions, 10, icon(
                Material.EXPERIENCE_BOTTLE,
                "Minecraft XPへ交換",
                "サーバーXPをMinecraft XPへ交換します。"),
                () -> openOptions(
                        player,
                        "Minecraft XP交換",
                        ExchangeCatalog.XP,
                        Material.EXPERIENCE_BOTTLE,
                        selectionHandler,
                        () -> open(player, selectionHandler)));
        add(inventory, actions, 11, icon(
                Material.EMERALD,
                "資源へ交換",
                "サーバーXPを資源へ交換します。",
                "資源の種類を選んでから、個数と必要XPを確認できます。"),
                () -> openResourceGroups(player, selectionHandler));
        add(inventory, actions, 12, icon(
                Material.DIAMOND,
                "手持ちエメラルドを交換",
                "エメラルドをダイヤモンドへ交換します。"),
                () -> openOptions(
                        player,
                        "エメラルド交換",
                        ExchangeCatalog.EMERALD_DIAMOND,
                        Material.DIAMOND,
                        selectionHandler,
                        () -> open(player, selectionHandler)));
        add(inventory, actions, 13, icon(
                Material.CHEST,
                "資材をサーバーXPへ交換",
                "通常の対象資材を64個単位で買い取ります。",
                "1日の買取上限: 1,500 サーバーXP（毎日0時・日本時間に更新）",
                "本日の残り枠は処理時に確認し、超過時は回収しません。",
                "獲得後は「資源へ交換」からエメラルドにもできます。"),
                () -> openBuybackMaterials(player, selectionHandler));
        add(inventory, actions, 15, icon(
                Material.BOOK,
                "サーバーXP残高を確認",
                "現在のサーバーXPを本人だけに表示します。"),
                () -> finish(player, ExchangeSelection.balance(), selectionHandler));
        add(inventory, actions, 16, icon(Material.BARRIER, "閉じる"), player::closeInventory);
        player.openInventory(inventory);
        return true;
    }

    private void openResourceGroups(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, "資源交換", actions);
        int[] slots = {11, 13, 15};
        for (int index = 0; index < ExchangeCatalog.RESOURCE_GROUPS.size(); index++) {
            ExchangeCatalog.ResourceGroup group = ExchangeCatalog.RESOURCE_GROUPS.get(index);
            Material material = resourceMaterial(group.target());
            add(inventory, actions, slots[index], icon(
                    material,
                    group.itemName(),
                    "個数と必要サーバーXPを選びます。",
                    "交換候補: " + group.amountsLabel()),
                    () -> openOptions(
                            player,
                            group.itemName() + "へ交換",
                            group.options(),
                            material,
                            selectionHandler,
                            () -> openResourceGroups(player, selectionHandler)));
        }
        add(inventory, actions, 22, icon(Material.ARROW, "戻る"),
                () -> open(player, selectionHandler));
        player.openInventory(inventory);
    }

    private void openBuybackMaterials(
            Player player, Consumer<ExchangeSelection> selectionHandler) {
        List<MaterialBuybackCatalog.Rate> available = MaterialBuybackCatalog.RATES.stream()
                .filter(rate -> MaterialBuybackCatalog.plainCount(player, rate.material())
                        >= MaterialBuybackCatalog.STACK_SIZE)
                .toList();
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, "資材買取", actions);
        if (available.isEmpty()) {
            inventory.setItem(13, icon(
                    Material.BARRIER,
                    "交換できる資材がありません",
                    "対象資材を64個以上インベントリへ入れてください。",
                    "対象: 土・砂・砂岩・深層岩・深層岩の丸石・凝灰岩"));
        } else {
            int[] slots = {10, 11, 12, 13, 14, 15};
            for (int index = 0; index < available.size(); index++) {
                MaterialBuybackCatalog.Rate rate = available.get(index);
                int count = MaterialBuybackCatalog.plainCount(player, rate.material());
                int exchangeable = count / MaterialBuybackCatalog.STACK_SIZE
                        * MaterialBuybackCatalog.STACK_SIZE;
                add(inventory, actions, slots[index], icon(
                        rate.material(),
                        rate.itemName(),
                        "通常品の所持: " + count + "個",
                        "64個単位で交換可能: " + exchangeable + "個",
                        "64個 → " + rate.rewardXpPerStack() + " サーバーXP",
                        "名前や特殊データのない通常アイテムだけが対象です。"),
                        () -> openBuybackAmounts(player, rate, selectionHandler));
            }
        }
        add(inventory, actions, 22, icon(Material.ARROW, "戻る"),
                () -> open(player, selectionHandler));
        player.openInventory(inventory);
    }

    private void openBuybackAmounts(
            Player player,
            MaterialBuybackCatalog.Rate rate,
            Consumer<ExchangeSelection> selectionHandler) {
        int available = MaterialBuybackCatalog.plainCount(player, rate.material());
        int all = available / MaterialBuybackCatalog.STACK_SIZE
                * MaterialBuybackCatalog.STACK_SIZE;
        if (all < MaterialBuybackCatalog.STACK_SIZE) {
            openBuybackMaterials(player, selectionHandler);
            return;
        }
        List<MaterialBuybackCatalog.QuantityOption> options =
                MaterialBuybackCatalog.quantityOptions(rate, all);
        openBuybackQuantityOptions(
                player,
                rate.itemName() + "の買取数",
                rate,
                options,
                selectionHandler,
                () -> openBuybackMaterials(player, selectionHandler));
    }

    private void openBuybackQuantityOptions(
            Player player,
            String title,
            MaterialBuybackCatalog.Rate rate,
            List<MaterialBuybackCatalog.QuantityOption> options,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, title, actions);
        int firstSlot = options.size() <= 5 ? 11 : 9;
        for (int index = 0; index < options.size(); index++) {
            MaterialBuybackCatalog.QuantityOption option = options.get(index);
            ExchangeSelection selection =
                    MaterialBuybackCatalog.selection(rate, option.itemCount());
            add(inventory, actions, firstSlot + index, icon(
                            rate.material(),
                            option.label(),
                            selection.description()),
                    () -> openConfirmation(
                            player,
                            selection,
                            rate.material(),
                            selectionHandler,
                            () -> openBuybackQuantityOptions(
                                    player,
                                    title,
                                    rate,
                                    options,
                                    selectionHandler,
                                    backAction)));
        }
        add(inventory, actions, 22, icon(Material.ARROW, "戻る"), backAction);
        player.openInventory(inventory);
    }

    private void openOptions(
            Player player,
            String title,
            List<ExchangeSelection> options,
            Material material,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, title, actions);
        int firstSlot = options.size() <= 5 ? 11 : 9;
        for (int index = 0; index < options.size(); index++) {
            ExchangeSelection selection = options.get(index);
            int slot = firstSlot + index;
            Material optionMaterial = optionMaterial(selection, material);
            add(inventory, actions, slot, icon(optionMaterial, selection.description()),
                    () -> openConfirmation(
                            player,
                            selection,
                            optionMaterial,
                            selectionHandler,
                            () -> openOptions(
                                    player,
                                    title,
                                    options,
                                    material,
                                    selectionHandler,
                                    backAction)));
        }
        add(inventory, actions, 22, icon(Material.ARROW, "戻る"), backAction);
        player.openInventory(inventory);
    }

    private void openConfirmation(
            Player player,
            ExchangeSelection selection,
            Material material,
            Consumer<ExchangeSelection> selectionHandler,
            Runnable backAction) {
        Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inventory = create(player, "交換内容の確認", actions);
        inventory.setItem(13, selection.kind() == ExchangeKind.MATERIAL_BUYBACK
                ? icon(
                        material,
                        selection.description(),
                        "名前や特殊データのない通常アイテムだけを回収します。",
                        "獲得後は「資源へ交換」からエメラルドにもできます。")
                : icon(material, selection.description()));
        add(inventory, actions, 11, icon(
                Material.LIME_CONCRETE,
                "交換する",
                "表示された内容で交換を申し込みます。"),
                () -> finish(player, selection, selectionHandler));
        add(inventory, actions, 15, icon(Material.ARROW, "戻る"), backAction);
        player.openInventory(inventory);
    }

    private static void finish(
            Player player,
            ExchangeSelection selection,
            Consumer<ExchangeSelection> selectionHandler) {
        player.closeInventory();
        selectionHandler.accept(selection);
    }

    private Inventory create(Player player, String title, Map<Integer, Runnable> actions) {
        MenuHolder holder = new MenuHolder(player.getUniqueId(), actions);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, Component.text(title));
        holder.setInventory(inventory);
        return inventory;
    }

    private static void add(
            Inventory inventory,
            Map<Integer, Runnable> actions,
            int slot,
            ItemStack item,
            Runnable action) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    private static ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA));
        if (lore.length > 0) {
            meta.lore(List.of(lore).stream()
                    .map(line -> Component.text(line, NamedTextColor.GRAY))
                    .toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Material optionMaterial(ExchangeSelection selection, Material fallback) {
        if (selection.kind() != ExchangeKind.RESOURCE) {
            return fallback;
        }
        return switch (selection.target()) {
            case "minecraft:diamond" -> Material.DIAMOND;
            case "minecraft:emerald" -> Material.EMERALD;
            case "minecraft:gunpowder" -> Material.GUNPOWDER;
            default -> fallback;
        };
    }

    private static Material resourceMaterial(String target) {
        return switch (target) {
            case "minecraft:diamond" -> Material.DIAMOND;
            case "minecraft:emerald" -> Material.EMERALD;
            case "minecraft:gunpowder" -> Material.GUNPOWDER;
            default -> Material.BARRIER;
        };
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId().equals(player.getUniqueId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        Runnable action = holder.actions().get(event.getRawSlot());
        if (action != null) {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID playerId;
        private final Map<Integer, Runnable> actions;
        private Inventory inventory;

        private MenuHolder(UUID playerId, Map<Integer, Runnable> actions) {
            this.playerId = playerId;
            this.actions = actions;
        }

        private UUID playerId() {
            return playerId;
        }

        private Map<Integer, Runnable> actions() {
            return actions;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
