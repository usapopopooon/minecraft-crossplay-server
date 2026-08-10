package net.usapo.eventbridge;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class VoiceBonusRegistry {
    private final Set<UUID> activePlayers = new HashSet<>();

    void activate(UUID playerId) {
        activePlayers.add(playerId);
    }

    void deactivate(UUID playerId) {
        activePlayers.remove(playerId);
    }

    boolean isActive(UUID playerId) {
        return activePlayers.contains(playerId);
    }
}
