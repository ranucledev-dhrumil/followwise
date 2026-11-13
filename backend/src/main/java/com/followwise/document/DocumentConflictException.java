package com.followwise.document;

public class DocumentConflictException extends RuntimeException {

    private final String conflictType;
    private final Integer currentVersion;

    public DocumentConflictException(String conflictType, String message) {
        this(conflictType, message, null);
    }

    public DocumentConflictException(String conflictType, String message, Integer currentVersion) {
        super(message);
        this.conflictType = conflictType;
        this.currentVersion = currentVersion;
    }

    public String getConflictType() {
        return conflictType;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }
}
