package com.followwise.ledger;

import com.followwise.appdef.ValidationError;

import java.util.List;

public class LedgerValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public LedgerValidationException(List<ValidationError> errors) {
        super("Validation failed");
        this.errors = errors;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
