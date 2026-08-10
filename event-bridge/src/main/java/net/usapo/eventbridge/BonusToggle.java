package net.usapo.eventbridge;

final class BonusToggle {
    private BonusToggle() {}

    static boolean isEnabled(String configuredValue) {
        return configuredValue == null || Boolean.parseBoolean(configuredValue.strip());
    }
}
