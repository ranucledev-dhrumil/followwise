package com.followwise.app;

import com.followwise.appdef.ValidationError;

import java.util.List;

public class AppValidationException extends RuntimeException {
    private final List<ValidationError> errors;

    public AppValidationException(List<ValidationError> errors) {
        super("Validation failed");
        this.errors = errors != null ? (errors.size() > 20 ? errors.subList(0, 20) : errors) : List.of();
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
