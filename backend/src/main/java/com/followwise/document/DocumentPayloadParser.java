package com.followwise.document;

import com.followwise.app.AppBadRequestException;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

@Component
public class DocumentPayloadParser {

    private final AppPayloadParser appPayloadParser;

    public record ParsedAssignChecklistRequest(String checklistId) {}

    public DocumentPayloadParser(AppPayloadParser appPayloadParser) {
        this.appPayloadParser = appPayloadParser;
    }

    public JsonNode parseStrictJson(byte[] rawBody) {
        try {
            return appPayloadParser.parseStrictJson(rawBody);
        } catch (AppValidationException e) {
            throw new DocumentValidationException(e.getErrors());
        } catch (AppBadRequestException e) {
            String msg = e.getMessage();
            if ("missing payload".equals(msg)
                    || "maximum nesting depth exceeded".equals(msg)
                    || "invalid JSON syntax".equals(msg)
                    || "request body must be a JSON object".equals(msg)
                    || "unknown property in request envelope".equals(msg)) {
                throw new DocumentBadRequestException(msg);
            }
            throw new DocumentBadRequestException("invalid request payload");
        }
    }

    public ParsedAssignChecklistRequest parseAssignChecklistRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new DocumentBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("checklistId");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new DocumentBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("checklistId") || !obj.get("checklistId").isTextual()) {
            throw new DocumentBadRequestException("missing or invalid checklistId");
        }
        String checklistId = obj.get("checklistId").asText().trim();
        if (checklistId.isEmpty() || checklistId.length() > 40) {
            throw new DocumentBadRequestException("invalid checklistId");
        }
        return new ParsedAssignChecklistRequest(checklistId);
    }
}
