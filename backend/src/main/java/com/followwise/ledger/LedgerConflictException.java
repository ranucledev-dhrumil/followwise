package com.followwise.ledger;

public class LedgerConflictException extends RuntimeException {

    private final String conflictType;

    public LedgerConflictException(String conflictType, String message) {
        super(message);
        this.conflictType = conflictType;
    }

    public String getConflictType() {
        return conflictType;
    }
}
