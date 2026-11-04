package com.followwise.party;

import com.followwise.app.AppBadRequestException;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

@Component
public class PartyPayloadParser {

    private final AppPayloadParser appPayloadParser;

    public record ParsedCreateRequest(String type, JsonNode data) {
    }

    public record ParsedUpdateRequest(int expectedVersion, JsonNode data) {
    }

    public PartyPayloadParser(AppPayloadParser appPayloadParser) {
        this.appPayloadParser = appPayloadParser;
    }

    public JsonNode parseStrictJson(byte[] rawBody) {
        try {
            return appPayloadParser.parseStrictJson(rawBody);
        } catch (AppValidationException e) {
            throw new PartyValidationException(e.getErrors());
        } catch (AppBadRequestException e) {
            throw new PartyBadRequestException(e.getMessage());
        }
    }

    public ParsedCreateRequest parseCreateRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new PartyBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("type", "data");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new PartyBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("type") || !obj.get("type").isTextual()) {
            throw new PartyBadRequestException("missing or invalid type");
        }
        String type = obj.get("type").asText();

        if (!obj.hasNonNull("data") || !obj.get("data").isObject()) {
            throw new PartyBadRequestException("missing or invalid data");
        }
        JsonNode data = obj.get("data");

        return new ParsedCreateRequest(type, data);
    }

    public ParsedUpdateRequest parseUpdateRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new PartyBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion", "data");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new PartyBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("expectedVersion") || !obj.get("expectedVersion").isIntegralNumber()) {
            throw new PartyBadRequestException("expectedVersion must be an integer >= 1");
        }
        int expectedVersion = obj.get("expectedVersion").asInt();
        if (expectedVersion < 1) {
            throw new PartyBadRequestException("expectedVersion must be an integer >= 1");
        }

        if (!obj.hasNonNull("data") || !obj.get("data").isObject()) {
            throw new PartyBadRequestException("missing or invalid data");
        }
        JsonNode data = obj.get("data");

        return new ParsedUpdateRequest(expectedVersion, data);
    }
}
