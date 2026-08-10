package net.usapo.eventbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

final class ExperienceAccumulator {
    private final ActivityPublisher publisher;
    private final Map<UUID, PendingExperience> pending = new HashMap<>();

    ExperienceAccumulator(ActivityPublisher publisher) {
        this.publisher = publisher;
    }

    void add(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        pending.merge(
                player.getUniqueId(),
                new PendingExperience(player, amount),
                (current, added) -> new PendingExperience(
                        added.player(), saturatingAdd(current.amount(), added.amount())));
    }

    void flush(Player player) {
        PendingExperience experience = pending.remove(player.getUniqueId());
        publish(experience);
    }

    void flushAll() {
        PendingExperience[] snapshot = pending.values().toArray(PendingExperience[]::new);
        pending.clear();
        for (PendingExperience experience : snapshot) {
            publish(experience);
        }
    }

    private void publish(PendingExperience experience) {
        if (experience != null) {
            publisher.publish(
                    ActivityKind.EXPERIENCE, experience.player(), experience.amount());
        }
    }

    private static int saturatingAdd(int first, int second) {
        return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
    }

    private record PendingExperience(Player player, int amount) {}
}
