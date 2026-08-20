package net.usapo.eventbridge;

final class QuestActionException extends RuntimeException {
    private final String code;

    QuestActionException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
