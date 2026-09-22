package net.usapo.eventbridge;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

final class WorldTravelPrompt implements Listener {
    static final String TITLE = "ワールド移動の確認";
    static final List<String> EXPLANATION = List.of(
            "持ち物（ホットバー含む）・装備・オフハンドと",
            "エンダーチェストは今いる側に保存されます。",
            "移動先の持ち物に切り替わり、",
            "戻ると保存した持ち物に戻ります。",
            "経験値（XP）とレベルは変わりません。");
    static final int CONFIRM_SLOT = 11;
    static final int CANCEL_SLOT = 15;

    private final JavaPlugin plugin;
    private final BedrockWorldTravelPrompt bedrock;

    WorldTravelPrompt(JavaPlugin plugin, BedrockWorldTravelPrompt bedrock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bedrock = bedrock;
    }

    boolean open(Player player, String destinationLabel, Runnable confirm, Runnable cancel) {
        Objects.requireNonNull(confirm, "confirm");
        Objects.requireNonNull(cancel, "cancel");
        if (!player.isOnline()) {
            return false;
        }
        try {
            if (bedrock != null && bedrock.isBedrockPlayer(player)) {
                return bedrock.open(player, destinationLabel, confirm, cancel);
            }
            PromptHolder holder = new PromptHolder(player, confirm, cancel);
            Inventory inventory = Bukkit.createInventory(
                    holder, 27, Component.text(TITLE).decoration(TextDecoration.ITALIC, false));
            holder.attach(inventory);
            inventory.setItem(4, JavaChestMenus.icon(
                    Material.PAPER, "移動先: " + destinationLabel, EXPLANATION));
            inventory.setItem(CONFIRM_SLOT, JavaChestMenus.icon(
                    Material.LIME_DYE, "移動する", List.of("持ち物の切り替えを確認して移動")));
            inventory.setItem(CANCEL_SLOT, JavaChestMenus.icon(
                    Material.RED_DYE, "やめる", List.of("移動せず、この画面を閉じる")));
            return player.openInventory(inventory) != null;
        } catch (RuntimeException error) {
            plugin.getLogger().warning(
                    "Could not open world travel confirmation for " + player.getUniqueId()
                            + ": " + error.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PromptHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() != holder.owner
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot != CONFIRM_SLOT && slot != CANCEL_SLOT) {
            return;
        }
        Runnable resolution = holder.resolve(slot == CONFIRM_SLOT);
        if (resolution == null) {
            return;
        }
        // Resolve before closing, but defer the inventory change out of the click event.
        runOnMain(() -> {
            if (holder.owner.getOpenInventory().getTopInventory() == holder.getInventory()) {
                holder.owner.closeInventory();
            }
            resolution.run();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PromptHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PromptHolder holder)
                || event.getPlayer() != holder.owner) {
            return;
        }
        Runnable resolution = holder.resolve(false);
        if (resolution != null) {
            runOnMain(resolution);
        }
    }

    private void runOnMain(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    static final class PromptHolder implements InventoryHolder {
        private final Player owner;
        private final Runnable confirm;
        private final Runnable cancel;
        private final AtomicBoolean resolved = new AtomicBoolean();
        private Inventory inventory;

        PromptHolder(Player owner, Runnable confirm, Runnable cancel) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.confirm = Objects.requireNonNull(confirm, "confirm");
            this.cancel = Objects.requireNonNull(cancel, "cancel");
        }

        void attach(Inventory inventory) {
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }

        Runnable resolve(boolean accepted) {
            return resolved.compareAndSet(false, true) ? (accepted ? confirm : cancel) : null;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
