package net.usapo.eventbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

final class JavaChestMenus implements Listener {
    private static final int NUMBER_MENU_SIZE = 54;

    private final JavaPlugin plugin;

    JavaChestMenus(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    boolean open(
            Player player,
            String title,
            int size,
            Map<Integer, MenuEntry> entries) {
        if (!player.isOnline()) {
            return false;
        }
        try {
            MenuHolder holder = new MenuHolder(player.getUniqueId(), entries);
            Inventory inventory = Bukkit.createInventory(
                    holder,
                    size,
                    Component.text(title).decoration(TextDecoration.ITALIC, false));
            holder.attach(inventory);
            entries.forEach((slot, entry) -> {
                if (slot >= 0 && slot < size) {
                    inventory.setItem(slot, entry.icon());
                }
            });
            player.openInventory(inventory);
            return true;
        } catch (RuntimeException error) {
            plugin.getLogger().warning(
                    "Could not open Java chest menu for " + player.getUniqueId() + ": "
                            + error.getMessage());
            player.sendMessage("画面を開けませんでした。コマンド入力をお試しください。");
            return false;
        }
    }

    void openNumberInput(
            Player player,
            String title,
            String prompt,
            int minimum,
            int maximum,
            ItemStack contextIcon,
            List<Integer> quickValues,
            boolean closeOnCompletion,
            Consumer<Integer> completion,
            Runnable backAction) {
        NumberInputState state = new NumberInputState();
        Map<Integer, MenuEntry> entries = new HashMap<>();
        String range = maximum == Integer.MAX_VALUE
                ? minimum + "以上"
                : minimum + "〜" + String.format("%,d", maximum);
        entries.put(2, display(contextIcon, List.of(prompt, "入力範囲: " + range)));
        entries.put(6, display(numberDisplay(state), List.of(prompt)));

        int[] quickSlots = {11, 12, 13, 14, 15};
        List<Integer> availableQuickValues = quickValues.stream()
                .filter(value -> value >= minimum && value <= maximum)
                .distinct()
                .limit(quickSlots.length)
                .toList();
        for (int index = 0; index < availableQuickValues.size(); index++) {
            int value = availableQuickValues.get(index);
            entries.put(quickSlots[index], updateAction(
                    Material.YELLOW_STAINED_GLASS_PANE,
                    String.format("%,d", value),
                    List.of("この値を入力"),
                    context -> {
                        state.digits(Integer.toString(value));
                        updateNumberDisplay(context.inventory(), state, prompt);
                    }));
        }

        int[] digitSlots = {21, 22, 23, 30, 31, 32, 39, 40, 41};
        for (int digit = 1; digit <= 9; digit++) {
            int selectedDigit = digit;
            entries.put(digitSlots[digit - 1], updateAction(
                    Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    Integer.toString(digit),
                    List.of(),
                    context -> {
                        String next = appendDigit(state.digits(), selectedDigit, maximum);
                        if (next.equals(state.digits())) {
                            player.sendMessage("入力できる最大値は "
                                    + String.format("%,d", maximum) + " です。");
                        }
                        state.digits(next);
                        updateNumberDisplay(context.inventory(), state, prompt);
                    }));
        }
        entries.put(45, updateAction(
                Material.RED_DYE,
                "全部消す",
                List.of(),
                context -> {
                    state.digits("");
                    updateNumberDisplay(context.inventory(), state, prompt);
                }));
        entries.put(47, updateAction(
                Material.ORANGE_DYE,
                "1文字消す",
                List.of(),
                context -> {
                    state.digits(removeLastDigit(state.digits()));
                    updateNumberDisplay(context.inventory(), state, prompt);
                }));
        entries.put(49, updateAction(
                Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                "0",
                List.of(),
                context -> {
                    String next = appendDigit(state.digits(), 0, maximum);
                    if (next.equals(state.digits()) && !state.digits().equals("0")) {
                        player.sendMessage("入力できる最大値は "
                                + String.format("%,d", maximum) + " です。");
                    }
                    state.digits(next);
                    updateNumberDisplay(context.inventory(), state, prompt);
                }));
        entries.put(51, updateAction(
                Material.LIME_DYE,
                "この数字で決定",
                List.of(),
                context -> {
                    int value = parseDigits(state.digits());
                    if (value < minimum || value > maximum) {
                        player.sendMessage("入力値は " + minimum + "〜"
                                + String.format("%,d", maximum) + " で指定してください。");
                        return;
                    }
                    if (closeOnCompletion) {
                        context.player().closeInventory();
                    }
                    runLater(context.player(), () -> completion.accept(value));
                }));
        entries.put(53, action(Material.ARROW, "戻る", List.of(), backAction));
        open(player, title, NUMBER_MENU_SIZE, entries);
    }

    private static void updateNumberDisplay(
            Inventory inventory, NumberInputState state, String prompt) {
        inventory.setItem(6, withLore(numberDisplay(state), List.of(prompt)));
    }

    private static ItemStack numberDisplay(NumberInputState state) {
        String shown = state.digits().isEmpty()
                ? "未入力"
                : String.format("%,d", parseDigits(state.digits()));
        return icon(Material.PAPER, "現在の入力: " + shown, List.of());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId().equals(player.getUniqueId())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        MenuEntry selected = holder.actionAt(rawSlot);
        if (selected == null) {
            return;
        }
        MenuContext context = new MenuContext(player, top);
        switch (selected.activation()) {
            case UPDATE -> selected.action().accept(context);
            case NAVIGATE -> runLater(player, () -> selected.action().accept(context));
            case TERMINAL -> {
                player.closeInventory();
                runLater(player, () -> selected.action().accept(context));
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            event.setCancelled(true);
        }
    }

    static MenuEntry action(
            Material material, String name, List<String> lore, Runnable action) {
        return entry(icon(material, name, lore), action, Activation.NAVIGATE);
    }

    static MenuEntry terminalAction(
            Material material, String name, List<String> lore, Runnable action) {
        return entry(icon(material, name, lore), action, Activation.TERMINAL);
    }

    static MenuEntry updateAction(
            Material material,
            String name,
            List<String> lore,
            Consumer<MenuContext> action) {
        return new MenuEntry(icon(material, name, lore), action, Activation.UPDATE);
    }

    static MenuEntry updateAction(ItemStack icon, Consumer<MenuContext> action) {
        return new MenuEntry(icon, action, Activation.UPDATE);
    }

    static MenuEntry display(Material material, String name, List<String> lore) {
        return new MenuEntry(icon(material, name, lore), null, Activation.UPDATE);
    }

    static MenuEntry action(ItemStack icon, List<String> lore, Runnable action) {
        return entry(withLore(icon, lore), action, Activation.NAVIGATE);
    }

    static MenuEntry terminalAction(ItemStack icon, List<String> lore, Runnable action) {
        return entry(withLore(icon, lore), action, Activation.TERMINAL);
    }

    static MenuEntry display(ItemStack icon, List<String> lore) {
        return new MenuEntry(withLore(icon, lore), null, Activation.UPDATE);
    }

    private static MenuEntry entry(
            ItemStack icon, Runnable action, Activation activation) {
        return new MenuEntry(icon, ignored -> action.run(), activation);
    }

    private void runLater(Player player, Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(styled(name, NamedTextColor.WHITE));
        meta.lore(lore.stream().map(line -> styled(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack withLore(ItemStack original, List<String> addedLore) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        if (!lore.isEmpty() && !addedLore.isEmpty()) {
            lore.add(Component.empty());
        }
        addedLore.stream()
                .map(line -> styled(line, NamedTextColor.GRAY))
                .forEach(lore::add);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component styled(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static String appendDigit(String current, int digit, int maximum) {
        if (digit < 0 || digit > 9) {
            throw new IllegalArgumentException("digit must be between 0 and 9");
        }
        String next = current.equals("0") ? Integer.toString(digit) : current + digit;
        if (next.isEmpty()) {
            next = Integer.toString(digit);
        }
        try {
            long parsed = Long.parseLong(next);
            return parsed <= maximum ? Long.toString(parsed) : current;
        } catch (NumberFormatException error) {
            return current;
        }
    }

    static String removeLastDigit(String current) {
        return current.isEmpty() ? "" : current.substring(0, current.length() - 1);
    }

    static int parseDigits(String digits) {
        if (digits == null || digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    record MenuContext(Player player, Inventory inventory) {}

    private enum Activation {
        UPDATE,
        NAVIGATE,
        TERMINAL
    }

    record MenuEntry(
            ItemStack icon, Consumer<MenuContext> action, Activation activation) {
        MenuEntry {
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(activation, "activation");
            icon = icon.clone();
        }

        @Override
        public ItemStack icon() {
            return icon.clone();
        }
    }

    static final class MenuHolder implements InventoryHolder {
        private final UUID playerId;
        private final Map<Integer, MenuEntry> actions;
        private Inventory inventory;

        MenuHolder(UUID playerId, Map<Integer, MenuEntry> entries) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.actions = new HashMap<>();
            entries.forEach((slot, entry) -> {
                if (entry.action() != null) {
                    actions.put(slot, entry);
                }
            });
        }

        void attach(Inventory inventory) {
            if (this.inventory != null) {
                throw new IllegalStateException("inventory already attached");
            }
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }

        UUID playerId() {
            return playerId;
        }

        MenuEntry actionAt(int slot) {
            return actions.get(slot);
        }

        @Override
        public Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("inventory not attached");
            }
            return inventory;
        }
    }

    private static final class NumberInputState {
        private String digits = "";

        String digits() {
            return digits;
        }

        void digits(String digits) {
            this.digits = Objects.requireNonNull(digits, "digits");
        }
    }
}
