package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MaterialBuybackPendingRegistryTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void onlyTheBoundRequestCanReleaseThePlayersPendingBuyback() {
        MaterialBuybackPendingRegistry registry = new MaterialBuybackPendingRegistry();
        registry.register(PLAYER_ID, REQUEST_ID);

        assertEquals(
                MaterialBuybackPendingRegistry.ReleaseStatus.REQUEST_MISMATCH,
                registry.release(PLAYER_ID, UUID.randomUUID()));
        assertEquals(REQUEST_ID, registry.pendingRequest(PLAYER_ID).orElseThrow());
        assertEquals(
                MaterialBuybackPendingRegistry.ReleaseStatus.RELEASED,
                registry.release(PLAYER_ID, REQUEST_ID));
        assertEquals(
                MaterialBuybackPendingRegistry.ReleaseStatus.NOT_PENDING,
                registry.release(PLAYER_ID, REQUEST_ID));
    }
}
