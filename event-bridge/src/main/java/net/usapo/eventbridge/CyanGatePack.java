package net.usapo.eventbridge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

/** Offers only our optional Java pack; never replaces packs supplied by other plugins. */
final class CyanGatePack implements Listener {
    record Settings(UUID id, String url, String sha1, String bedrockSha256, String mappingSha256) {
        Settings {
            if (id == null || !url.startsWith("https://raw.githubusercontent.com/usapopopooon/"
                    + "minecraft-crossplay-server/") || !sha1.matches("[0-9a-f]{40}")
                    || !bedrockSha256.matches("[0-9a-f]{64}")
                    || !mappingSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid cyan gate pack metadata");
            }
        }
    }

    private final Settings settings;
    private final Predicate<Player> bedrock;
    private final boolean bedrockInstalled;
    private final Consumer<Runnable> schedule;
    private final Map<UUID, Player> offered = new HashMap<>();
    private final Map<UUID, Player> loaded = new HashMap<>();

    CyanGatePack(JavaPlugin plugin) {
        this(load(plugin), player -> plugin.getServer().getPluginManager().isPluginEnabled("floodgate")
                        && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId()),
                plugin.getDataFolder().toPath().getParent().resolve("Geyser-Spigot"),
                action -> plugin.getServer().getScheduler().runTaskLater(plugin, action, 40L));
        plugin.getLogger().info("Cyan gate pack metadata=" + (settings != null)
                + ", Bedrock artifacts verified=" + bedrockInstalled
                + "; Java resource pack is optional");
    }

    private CyanGatePack(Settings settings, Predicate<Player> bedrock, Path geyser,
                         Consumer<Runnable> schedule) {
        this(settings, bedrock, settings != null
                && matches(geyser.resolve("packs/cyan-portal-bedrock-v1.mcpack"), settings.bedrockSha256())
                && matches(geyser.resolve("custom_mappings/cyan-portal-v1.json"), settings.mappingSha256()),
                schedule);
    }

    CyanGatePack(Settings settings, Predicate<Player> bedrock, boolean bedrockInstalled,
                 Consumer<Runnable> schedule) {
        this.settings = settings;
        this.bedrock = bedrock;
        this.bedrockInstalled = bedrockInstalled;
        this.schedule = schedule;
    }

    private static Settings load(JavaPlugin plugin) {
        try (InputStream input = plugin.getResource("cyan-portal-pack.properties")) {
            if (input == null) throw new IllegalStateException("Pack metadata missing");
            Properties properties = new Properties();
            properties.load(input);
            return new Settings(UUID.fromString(properties.getProperty("uuid")),
                    properties.getProperty("url"), properties.getProperty("sha1"),
                    properties.getProperty("bedrock-sha256"), properties.getProperty("mapping-sha256"));
        } catch (Exception error) {
            plugin.getLogger().severe("Cyan gate pack unavailable; keeping particle fallback: "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    static boolean matches(Path file, String sha256) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest()).equals(sha256);
        } catch (Exception error) {
            return false;
        }
    }

    void offer(Player player) {
        if (settings == null || bedrock.test(player)) return;
        UUID playerId = player.getUniqueId();
        if (offered.get(playerId) == player) return;
        offered.put(playerId, player);
        loaded.remove(playerId);
        schedule.accept(() -> {
            if (player.isOnline() && offered.get(playerId) == player) {
                player.addResourcePack(settings.id(), settings.url(),
                        HexFormat.of().parseHex(settings.sha1()),
                        "ワールド移動ゲートを水色で表示します。拒否しても移動できます。", false);
            }
        });
    }

    boolean ready(Player player) {
        if (settings == null) return false;
        if (bedrock.test(player)) return bedrockInstalled;
        return loaded.get(player.getUniqueId()) == player;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { offer(event.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        offered.remove(player.getUniqueId(), player);
        loaded.remove(player.getUniqueId(), player);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (settings == null || !settings.id().equals(event.getID())) return;
        Player player = event.getPlayer();
        if (offered.get(player.getUniqueId()) != player) return;
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            loaded.put(player.getUniqueId(), player);
        } else {
            // ACCEPTED/DOWNLOADED are not proof that the models can yet be displayed.
            loaded.remove(player.getUniqueId(), player);
        }
    }
}
