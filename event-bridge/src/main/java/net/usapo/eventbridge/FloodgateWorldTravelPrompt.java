package net.usapo.eventbridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateWorldTravelPrompt implements BedrockWorldTravelPrompt {
    private final JavaPlugin plugin;
    private final FloodgateApi floodgate;

    FloodgateWorldTravelPrompt(JavaPlugin plugin) {
        this(plugin, FloodgateApi.getInstance());
    }

    FloodgateWorldTravelPrompt(JavaPlugin plugin, FloodgateApi floodgate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.floodgate = Objects.requireNonNull(floodgate, "floodgate");
    }

    @Override
    public boolean isBedrockPlayer(Player player) {
        return floodgate.isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean open(Player player, String destinationLabel, Runnable confirm, Runnable cancel) {
        Objects.requireNonNull(confirm, "confirm");
        Objects.requireNonNull(cancel, "cancel");
        if (!player.isOnline() || !isBedrockPlayer(player)) {
            return false;
        }
        AtomicBoolean resolved = new AtomicBoolean();
        ModalForm form = ModalForm.builder()
                .title(WorldTravelPrompt.TITLE)
                .content("移動先: " + destinationLabel + "\n\n"
                        + String.join("\n", WorldTravelPrompt.EXPLANATION))
                .button1("移動する")
                .button2("やめる")
                .validResultHandler(response -> resolve(
                        resolved, response.clickedFirst() ? confirm : cancel))
                // The installed Cumulus version dispatches invalid results correctly
                // through the Consumer overload, unlike its Runnable overload.
                .closedOrInvalidResultHandler(ignored -> resolve(resolved, cancel))
                .build();
        boolean sent = floodgate.sendForm(player.getUniqueId(), form);
        if (!sent) {
            resolved.set(true);
        }
        return sent;
    }

    private void resolve(AtomicBoolean resolved, Runnable action) {
        if (resolved.compareAndSet(false, true)) {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }
}
