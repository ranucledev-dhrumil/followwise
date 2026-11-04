package com.followwise.party;

import com.followwise.appdef.ValidationError;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class FieldValidator {

    private static final int MAX_ERRORS = 20;
    private static final int MAX_SERIALIZED_BYTES = 16384;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-\\(\\)\\s]{5,32}$");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final JsonMapper jsonMapper;

    public FieldValidator() {
        this.jsonMapper = JsonMapper.builder().build();
    }

    public FieldValidationResult validate(JsonNode typeNode, JsonNode data, JsonNode previousData, RefChecker refChecker) {
        List<ValidationError> errors = new ArrayList<>();

        if (typeNode == null || !typeNode.isObject()) {
            errors.add(new ValidationError("/type", "invalid type definition"));
            return new FieldValidationResult(errors, null, null);
        }

        if (typeNode.path("archived").asBoolean(false)) {
            errors.add(new ValidationError("/type", "archived type cannot be used"));
            return new FieldValidationResult(errors, null, null);
        }

        if (data == null || !data.isObject()) {
            errors.add(new ValidationError("/data", "data must be an object"));
            return new FieldValidationResult(errors, null, null);
        }

        ObjectNode inputData = (ObjectNode) data;
        ObjectNode normalizedData = jsonMapper.createObjectNode();

        // Map defined fields by id and maintain order
        JsonNode fieldsArray = typeNode.path("fields");
        Map<String, JsonNode> definedFields = new LinkedHashMap<>();
        if (fieldsArray.isArray()) {
            for (JsonNode f : fieldsArray) {
                if (f.hasNonNull("id")) {
                    definedFields.put(f.get("id").asText(), f);
                }
            }
        }

        // Check for unknown fields in submitted data
        for (var entry : inputData.properties()) {
            String fieldId = entry.getKey();
            if (!definedFields.containsKey(fieldId)) {
                addError(errors, "/data/" + fieldId, "unknown field");
                if (errors.size() >= MAX_ERRORS) {
                    return new FieldValidationResult(errors, null, null);
                }
            }
        }

        // Validate each defined field
        for (Map.Entry<String, JsonNode> fieldEntry : definedFields.entrySet()) {
            if (errors.size() >= MAX_ERRORS) {
                break;
            }

            String fieldId = fieldEntry.getKey();
            JsonNode fieldDef = fieldEntry.getValue();
            boolean isArchived = fieldDef.path("archived").asBoolean(false);
            boolean isRequired = fieldDef.path("required").asBoolean(false);
            String fieldType = fieldDef.path("type").asText("");

            boolean hasSubmitted = inputData.hasNonNull(fieldId);
            JsonNode submittedNode = inputData.get(fieldId);

            if (isArchived) {
                // Archived fields are read-only: their stored values are carried over unchanged on update and a different submitted value is an error
                if (previousData == null) {
                    // Create: cannot submit value for archived field
                    if (hasSubmitted) {
                        addError(errors, "/data/" + fieldId, "archived field is read-only");
                    }
                } else {
                    // Update: carry over previous value
                    JsonNode prevValue = previousData.get(fieldId);
                    if (prevValue != null && !prevValue.isNull()) {
                        normalizedData.set(fieldId, prevValue.deepCopy());
                    }
                    if (hasSubmitted) {
                        if (prevValue == null || prevValue.isNull() || !prevValue.equals(submittedNode)) {
                            addError(errors, "/data/" + fieldId, "archived field is read-only");
                        }
                    }
                }
                continue;
            }

            // Non-archived field
            if (!hasSubmitted || submittedNode.isNull()) {
                if (isRequired) {
                    addError(errors, "/data/" + fieldId, "required field missing");
                }
                continue;
            }

            // Submitted value present
            switch (fieldType) {
                case "text" -> validateText(fieldId, submittedNode, isRequired, normalizedData, errors);
                case "email" -> validateEmail(fieldId, submittedNode, isRequired, normalizedData, errors);
                case "phone" -> validatePhone(fieldId, submittedNode, isRequired, normalizedData, errors);
                case "date" -> validateDate(fieldId, submittedNode, isRequired, normalizedData, errors);
                case "integer" -> validateInteger(fieldId, submittedNode, normalizedData, errors);
                case "money" -> validateMoney(fieldId, submittedNode, normalizedData, errors);
                case "boolean" -> validateBoolean(fieldId, submittedNode, normalizedData, errors);
                case "select" -> validateSelect(fieldId, submittedNode, fieldDef, isRequired, normalizedData, errors);
                case "party_ref" -> validateRef(fieldId, submittedNode, fieldDef, previousData, refChecker, true, isRequired, normalizedData, errors);
                case "record_ref" -> validateRef(fieldId, submittedNode, fieldDef, previousData, refChecker, false, isRequired, normalizedData, errors);
                default -> addError(errors, "/data/" + fieldId, "unsupported field type");
            }
        }

        if (!errors.isEmpty()) {
            return new FieldValidationResult(errors, null, null);
        }

        // Compute display_name: first non-archived text-type field in definition order
        String computedDisplayName = null;
        String dispFieldId = null;
        if (fieldsArray.isArray()) {
            for (JsonNode f : fieldsArray) {
                boolean isArch = f.path("archived").asBoolean(false);
                String fType = f.path("type").asText("");
                if (!isArch && "text".equals(fType)) {
                    dispFieldId = f.path("id").asText();
                    break;
                }
            }
        }

        if (dispFieldId == null || !normalizedData.hasNonNull(dispFieldId) || normalizedData.get(dispFieldId).asText().trim().isEmpty()) {
            addError(errors, "/data/" + (dispFieldId != null ? dispFieldId : ""), "missing value for display name");
            return new FieldValidationResult(errors, null, null);
        }

        String rawDisp = normalizedData.get(dispFieldId).asText().trim();
        computedDisplayName = rawDisp.length() > 200 ? rawDisp.substring(0, 200) : rawDisp;

        // Check serialized data length <= 16384 bytes
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(normalizedData);
            if (bytes.length > MAX_SERIALIZED_BYTES) {
                addError(errors, "/data", "data exceeds maximum size of 16384 bytes");
                return new FieldValidationResult(errors, null, null);
            }
        } catch (Exception e) {
            addError(errors, "/data", "failed to serialize data");
            return new FieldValidationResult(errors, null, null);
        }

        return new FieldValidationResult(errors, normalizedData, computedDisplayName);
    }

    private void addError(List<ValidationError> errors, String path, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new ValidationError(path, message));
        }
    }

    private void validateText(String fieldId, JsonNode node, boolean isRequired, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid text format");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        if (trimmed.length() > 500) {
            addError(errors, "/data/" + fieldId, "text too long");
            return;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                addError(errors, "/data/" + fieldId, "text contains control characters");
                return;
            }
        }
        normalizedData.put(fieldId, trimmed);
    }

    private void validateEmail(String fieldId, JsonNode node, boolean isRequired, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid email format");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        if (trimmed.length() > 254 || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            addError(errors, "/data/" + fieldId, "invalid email format");
            return;
        }
        normalizedData.put(fieldId, trimmed.toLowerCase(Locale.ROOT));
    }

    private void validatePhone(String fieldId, JsonNode node, boolean isRequired, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid phone format");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        if (trimmed.length() < 5 || trimmed.length() > 32 || !PHONE_PATTERN.matcher(trimmed).matches()) {
            addError(errors, "/data/" + fieldId, "invalid phone format");
            return;
        }
        normalizedData.put(fieldId, trimmed);
    }

    private void validateDate(String fieldId, JsonNode node, boolean isRequired, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid date format");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        if (!DATE_PATTERN.matcher(trimmed).matches()) {
            addError(errors, "/data/" + fieldId, "invalid date format");
            return;
        }
        try {
            LocalDate.parse(trimmed, ISO_DATE_FORMATTER);
            normalizedData.put(fieldId, trimmed);
        } catch (DateTimeParseException e) {
            addError(errors, "/data/" + fieldId, "invalid date format");
        }
    }

    private void validateInteger(String fieldId, JsonNode node, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            addError(errors, "/data/" + fieldId, "invalid integer value");
            return;
        }
        long val = node.asLong();
        if (val < -1_000_000_000L || val > 1_000_000_000L) {
            addError(errors, "/data/" + fieldId, "invalid integer value");
            return;
        }
        normalizedData.put(fieldId, val);
    }

    private void validateMoney(String fieldId, JsonNode node, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            addError(errors, "/data/" + fieldId, "invalid money value");
            return;
        }
        long val = node.asLong();
        if (val < 0L || val > 100_000_000_000L) {
            addError(errors, "/data/" + fieldId, "invalid money value");
            return;
        }
        normalizedData.put(fieldId, val);
    }

    private void validateBoolean(String fieldId, JsonNode node, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isBoolean()) {
            addError(errors, "/data/" + fieldId, "invalid boolean value");
            return;
        }
        normalizedData.put(fieldId, node.asBoolean());
    }

    private void validateSelect(String fieldId, JsonNode node, JsonNode fieldDef, boolean isRequired, ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid select option");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        JsonNode options = fieldDef.path("options");
        boolean matched = false;
        if (options.isArray()) {
            for (JsonNode opt : options) {
                if (opt.hasNonNull("id") && opt.get("id").asText().equals(trimmed)) {
                    if (!opt.path("archived").asBoolean(false)) {
                        matched = true;
                    }
                    break;
                }
            }
        }
        if (!matched) {
            addError(errors, "/data/" + fieldId, "invalid select option");
            return;
        }
        normalizedData.put(fieldId, trimmed);
    }

    private void validateRef(String fieldId, JsonNode node, JsonNode fieldDef, JsonNode previousData,
                             RefChecker refChecker, boolean isParty, boolean isRequired,
                             ObjectNode normalizedData, List<ValidationError> errors) {
        if (!node.isTextual()) {
            addError(errors, "/data/" + fieldId, "invalid reference format");
            return;
        }
        String trimmed = node.asText().trim();
        if (trimmed.isEmpty()) {
            if (isRequired) {
                addError(errors, "/data/" + fieldId, "required field missing");
            }
            return;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            addError(errors, "/data/" + fieldId, "invalid reference format");
            return;
        }

        String targetType = fieldDef.path("target").asText("");
        if (refChecker != null) {
            boolean isUnchanged = (previousData != null && previousData.hasNonNull(fieldId) && previousData.get(fieldId).asText().equals(trimmed));
            RefChecker.TargetStatus status = isParty
                    ? refChecker.checkPartyRef(targetId, targetType)
                    : refChecker.checkRecordRef(targetId, targetType);

            if (status == RefChecker.TargetStatus.NOT_FOUND) {
                addError(errors, "/data/" + fieldId, "reference target not found");
                return;
            } else if (status == RefChecker.TargetStatus.ARCHIVED && !isUnchanged) {
                addError(errors, "/data/" + fieldId, "reference target is archived");
                return;
            }
        }

        normalizedData.put(fieldId, trimmed);
    }
}
