package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BonusToggleTest {
    @Test
    void defaultsToEnabledOutsideManagedDeployment() {
        assertTrue(BonusToggle.isEnabled(null));
    }

    @Test
    void acceptsExplicitTrueWithWhitespaceAndCaseDifferences() {
        assertTrue(BonusToggle.isEnabled(" TRUE "));
    }

    @Test
    void explicitFalseAndInvalidValuesFailClosed() {
        assertFalse(BonusToggle.isEnabled("false"));
        assertFalse(BonusToggle.isEnabled("invalid"));
    }
}
