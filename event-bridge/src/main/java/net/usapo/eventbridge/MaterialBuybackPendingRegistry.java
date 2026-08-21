package net.usapo.eventbridge;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MaterialBuybackPendingRegistry {
    enum ReleaseStatus {
        RELEASED("released"),
        NOT_PENDING("not_pending"),
        REQUEST_MISMATCH("request_mismatch");

        private final String wireName;

        ReleaseStatus(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();

    Optional<UUID> pendingRequest(UUID playerId) {
        return Optional.ofNullable(pending.get(playerId));
    }

    void register(UUID playerId, UUID requestId) {
        UUID existing = pending.putIfAbsent(playerId, requestId);
        if (existing != null && !existing.equals(requestId)) {
            throw new IllegalStateException("another material buyback is already pending");
        }
    }

    ReleaseStatus release(UUID playerId, UUID requestId) {
        UUID existing = pending.get(playerId);
        if (existing == null) {
            return ReleaseStatus.NOT_PENDING;
        }
        if (!existing.equals(requestId)) {
            return ReleaseStatus.REQUEST_MISMATCH;
        }
        return pending.remove(playerId, requestId)
                ? ReleaseStatus.RELEASED
                : ReleaseStatus.NOT_PENDING;
    }
}
