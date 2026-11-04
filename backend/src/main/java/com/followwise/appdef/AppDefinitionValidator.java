package com.followwise.appdef;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AppDefinitionValidator {

    public static final int MAX_BYTES = 65536;
    private static final int MAX_ERRORS = 20;

    private static final Map<String, String> KEYWORD_MESSAGES = Map.ofEntries(
            Map.entry("additionalProperties", "unknown property"),
            Map.entry("required", "missing required property"),
            Map.entry("pattern", "value does not match required format"),
            Map.entry("enum", "value not allowed"),
            Map.entry("const", "value not allowed"),
            Map.entry("type", "invalid type"),
            Map.entry("maxLength", "too long"),
            Map.entry("minLength", "too short"),
            Map.entry("maximum", "value out of range"),
            Map.entry("minimum", "value out of range"),
            Map.entry("maxItems", "too many items"),
            Map.entry("minItems", "not enough items"),
            Map.entry("uniqueItems", "duplicate items not allowed")
    );

    private final JsonMapper jsonMapper;
    private final Schema schema;
    private final SemanticValidator semanticValidator;

    public AppDefinitionValidator() {
        this(new SemanticValidator());
    }

    public AppDefinitionValidator(SemanticValidator semanticValidator) {
        this.semanticValidator = semanticValidator;

        JsonFactory jsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(20)
                        .maxStringLength(MAX_BYTES)
                        .build())
                .build();

        this.jsonMapper = JsonMapper.builder(jsonFactory).build();

        try {
            ClassPathResource resource = new ClassPathResource("appdefinition/app-definition-v1.schema.json");
            try (InputStream in = resource.getInputStream()) {
                this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load canonical App Definition schema", e);
        }
    }

    public List<ValidationError> validate(String json) {
        if (json == null) {
            return List.of(new ValidationError("#", "missing payload"));
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return validate(bytes);
    }

    public List<ValidationError> validate(byte[] jsonBytes) {
        if (jsonBytes == null || jsonBytes.length == 0) {
            return List.of(new ValidationError("#", "missing payload"));
        }
        if (jsonBytes.length > MAX_BYTES) {
            return List.of(new ValidationError("#", "oversized JSON"));
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(jsonBytes);
        } catch (StreamConstraintsException e) {
            return List.of(new ValidationError("#", "maximum nesting depth exceeded"));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.toLowerCase().contains("duplicate")) {
                return List.of(new ValidationError("#", "duplicate JSON key"));
            }
            if (msg.toLowerCase().contains("depth")) {
                return List.of(new ValidationError("#", "maximum nesting depth exceeded"));
            }
            return List.of(new ValidationError("#", "invalid JSON syntax"));
        }

        List<ValidationError> errors = new ArrayList<>();

        List<Error> schemaErrors = schema.validate(root);
        if (schemaErrors != null && !schemaErrors.isEmpty()) {
            for (Error err : schemaErrors) {
                String path = err.getInstanceLocation() != null ? err.getInstanceLocation().toString() : "#";
                if ("additionalProperties".equals(err.getKeyword()) && err.getProperty() != null && !err.getProperty().isEmpty()) {
                    if (path.equals("#") || path.isEmpty()) {
                        path = "/" + err.getProperty();
                    } else {
                        path = path + "/" + err.getProperty();
                    }
                } else if (path.isEmpty()) {
                    path = "#";
                }
                String keyword = err.getKeyword();
                String message = KEYWORD_MESSAGES.getOrDefault(keyword, "value not allowed");
                errors.add(new ValidationError(path, message));
                if (errors.size() >= MAX_ERRORS) {
                    break;
                }
            }
        }

        if (errors.isEmpty()) {
            List<ValidationError> semanticErrors = semanticValidator.validate(root);
            if (semanticErrors != null && !semanticErrors.isEmpty()) {
                errors.addAll(semanticErrors);
            }
        }

        if (errors.size() > MAX_ERRORS) {
            return errors.subList(0, MAX_ERRORS);
        }
        return errors;
    }

    public JsonMapper getJsonMapper() {
        return jsonMapper;
    }
}
