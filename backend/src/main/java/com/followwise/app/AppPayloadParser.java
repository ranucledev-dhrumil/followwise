package com.followwise.app;

import com.followwise.appdef.ValidationError;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AppPayloadParser {

    private final JsonMapper jsonMapper;

    public record ParsedCreateAppRequest(
            String templateKey,
            String name,
            String timezone
    ) {
    }

    public record ParsedPutDefinitionRequest(
            int expectedVersion,
            JsonNode definition,
            String note
    ) {
    }

    public AppPayloadParser() {
        tools.jackson.core.json.JsonFactory jsonFactory = tools.jackson.core.json.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(20)
                        .maxStringLength(81920)
                        .build())
                .build();
        this.jsonMapper = JsonMapper.builder(jsonFactory).build();
    }

    public JsonMapper getJsonMapper() {
        return jsonMapper;
    }

    public JsonNode parseStrictJson(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new AppBadRequestException("missing payload");
        }
        try {
            return jsonMapper.readTree(rawBody);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("duplicate")) {
                throw new AppValidationException(List.of(new ValidationError("#", "duplicate JSON key")));
            }
            if (msg.contains("depth")) {
                throw new AppBadRequestException("maximum nesting depth exceeded");
            }
            throw new AppBadRequestException("invalid JSON syntax");
        }
    }

    public ParsedCreateAppRequest parseCreateAppRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new AppBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("templateKey", "name", "timezone");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new AppBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("templateKey") || !obj.get("templateKey").isTextual()) {
            throw new AppBadRequestException("missing or invalid templateKey");
        }
        String templateKey = obj.get("templateKey").asText();

        if (!obj.hasNonNull("name") || !obj.get("name").isTextual()) {
            throw new AppBadRequestException("missing or invalid name");
        }
        String name = obj.get("name").asText();

        String timezone = null;
        if (obj.hasNonNull("timezone")) {
            if (!obj.get("timezone").isTextual()) {
                throw new AppBadRequestException("invalid timezone");
            }
            timezone = obj.get("timezone").asText();
        }

        return new ParsedCreateAppRequest(templateKey, name, timezone);
    }

    public ParsedPutDefinitionRequest parsePutDefinitionRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new AppBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion", "definition", "note");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new AppBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("expectedVersion") || !obj.get("expectedVersion").isIntegralNumber()) {
            throw new AppBadRequestException("expectedVersion must be an integer >= 1");
        }
        int expectedVersion = obj.get("expectedVersion").asInt();
        if (expectedVersion < 1) {
            throw new AppBadRequestException("expectedVersion must be an integer >= 1");
        }

        if (!obj.hasNonNull("definition") || !obj.get("definition").isObject()) {
            throw new AppBadRequestException("missing or invalid definition");
        }
        JsonNode definition = obj.get("definition");

        String note = null;
        if (obj.has("note") && !obj.get("note").isNull()) {
            if (!obj.get("note").isTextual()) {
                throw new AppBadRequestException("invalid note");
            }
            note = obj.get("note").asText();
            if (note.length() > 500 || !note.matches("^[^<>\\u0000-\\u001F\\u007F]*$")) {
                throw new AppBadRequestException("note must be at most 500 characters with no control characters");
            }
        }

        return new ParsedPutDefinitionRequest(expectedVersion, definition, note);
    }
}
