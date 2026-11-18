package com.followwise.reminder;

public class ReminderConflictException extends RuntimeException {
    private final String error;
    private final Integer currentVersion;

    public ReminderConflictException(String error, String message) {
        super(message);
        this.error = error;
        this.currentVersion = null;
    }

    public ReminderConflictException(String error, String message, int currentVersion) {
        super(message);
        this.error = error;
        this.currentVersion = currentVersion;
    }

    public String getError() {
        return error;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }
}
