package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class VoiceBonusCommandTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void mapsOnAndOffToTheExactUuid() {
        VoiceBonusRegistry registry = new VoiceBonusRegistry();
        VoiceBonusCommand command = new VoiceBonusCommand(registry, playerId -> true);

        assertEquals(
                VoiceBonusCommand.UpdateResult.UPDATED,
                command.update(PLAYER_ID.toString(), "on"));
        assertTrue(registry.isActive(PLAYER_ID));

        assertEquals(
                VoiceBonusCommand.UpdateResult.UPDATED,
                command.update(PLAYER_ID.toString(), "off"));
        assertFalse(registry.isActive(PLAYER_ID));
    }

    @Test
    void rejectsInvalidUuidAndStateWithoutChangingRegistry() {
        VoiceBonusRegistry registry = new VoiceBonusRegistry();
        VoiceBonusCommand command = new VoiceBonusCommand(registry, playerId -> true);

        assertEquals(
                VoiceBonusCommand.UpdateResult.INVALID_UUID,
                command.update("not-a-uuid", "on"));
        assertEquals(
                VoiceBonusCommand.UpdateResult.INVALID_STATE,
                command.update(PLAYER_ID.toString(), "enabled"));
        assertFalse(registry.isActive(PLAYER_ID));
    }

    @Test
    void refusesLateActivationAfterPlayerHasGoneOffline() {
        VoiceBonusRegistry registry = new VoiceBonusRegistry();
        VoiceBonusCommand command = new VoiceBonusCommand(registry, playerId -> false);

        assertEquals(
                VoiceBonusCommand.UpdateResult.PLAYER_OFFLINE,
                command.update(PLAYER_ID.toString(), "on"));
        assertFalse(registry.isActive(PLAYER_ID));
    }
}
