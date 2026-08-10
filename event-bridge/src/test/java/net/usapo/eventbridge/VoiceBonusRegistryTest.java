package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class VoiceBonusRegistryTest {
    @Test
    void activationAndDeactivationAreScopedToTheExactUuid() {
        VoiceBonusRegistry registry = new VoiceBonusRegistry();
        UUID active = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID other = UUID.fromString("33333333-3333-3333-3333-333333333333");

        registry.activate(active);

        assertTrue(registry.isActive(active));
        assertFalse(registry.isActive(other));

        registry.deactivate(active);
        assertFalse(registry.isActive(active));
    }
}
