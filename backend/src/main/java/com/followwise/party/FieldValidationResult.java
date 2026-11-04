package com.followwise.party;

import com.followwise.appdef.ValidationError;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record FieldValidationResult(
        List<ValidationError> errors,
        JsonNode normalizedData,
        String computedDisplayName
) {
    public boolean isValid() {
        return errors == null || errors.isEmpty();
    }
}
