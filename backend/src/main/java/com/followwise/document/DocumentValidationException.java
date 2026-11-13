package com.followwise.document;

import com.followwise.appdef.ValidationError;

import java.util.List;

public class DocumentValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public DocumentValidationException(List<ValidationError> errors) {
        super("Validation failed");
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
