package net.usapo.eventbridge;

import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class UsapoEventBridgePlugin extends JavaPlugin {
    private static final long EXPERIENCE_FLUSH_TICKS = 5 * 20L;

    private ExperienceAccumulator experience;

    @Override
    public void onEnable() {
        VoiceBonusRegistry voiceBonuses = new VoiceBonusRegistry();
        Objects.requireNonNull(getCommand("usapo-event-bridge"))
                .setExecutor(new VoiceBonusCommand(
                        voiceBonuses, playerId -> getServer().getPlayer(playerId) != null));
        if (!BonusToggle.isEnabled(System.getenv("USAPO_BONUSES_ENABLED"))) {
            getLogger().warning(
                    "Fishing, woodcutting, mining, natural experience, and voice XP bonuses disabled");
            return;
        }

        ActivityPublisher publisher = new EventLogPublisher(getLogger()::info);
        experience = new ExperienceAccumulator(publisher);
        getServer()
                .getPluginManager()
                .registerEvents(new ActivityListener(publisher, experience, voiceBonuses), this);
        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        experience::flushAll,
                        EXPERIENCE_FLUSH_TICKS,
                        EXPERIENCE_FLUSH_TICKS);
        getLogger().info("Fishing, woodcutting, mining, and experience event bridge enabled");
    }

    @Override
    public void onDisable() {
        if (experience != null) {
            experience.flushAll();
        }
    }
}
