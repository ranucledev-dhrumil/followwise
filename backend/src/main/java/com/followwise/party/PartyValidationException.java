package com.followwise.party;

import com.followwise.appdef.ValidationError;

import java.util.List;

public class PartyValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public PartyValidationException(List<ValidationError> errors) {
        super("Validation failed");
        this.errors = errors;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
