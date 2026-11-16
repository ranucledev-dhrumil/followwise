package com.followwise.document;

import com.followwise.app.AppBadRequestException;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Set;

@Component
public class DocumentPayloadParser {

    private final AppPayloadParser appPayloadParser;

    public record ParsedAssignChecklistRequest(String checklistId) {}
    public record ParsedApproveRequest(int expectedVersion, LocalDate expiresOn) {}
    public record ParsedRejectRequest(int expectedVersion, String reason) {}
    public record ParsedDueDateRequest(int expectedVersion, LocalDate dueDate) {}
    public record ParsedCancelRequest(int expectedVersion) {}

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

    public ParsedApproveRequest parseApproveRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new DocumentBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion", "expiresOn");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new DocumentBadRequestException("unknown property in request envelope");
            }
        }

        int expectedVersion = parseExpectedVersion(obj);
        LocalDate expiresOn = null;
        if (obj.hasNonNull("expiresOn")) {
            if (!obj.get("expiresOn").isTextual()) {
                throw new DocumentBadRequestException("invalid expiresOn");
            }
            try {
                expiresOn = LocalDate.parse(obj.get("expiresOn").asText().trim());
            } catch (Exception e) {
                throw new DocumentBadRequestException("invalid expiresOn");
            }
        }
        return new ParsedApproveRequest(expectedVersion, expiresOn);
    }

    public ParsedRejectRequest parseRejectRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new DocumentBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion", "reason");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new DocumentBadRequestException("unknown property in request envelope");
            }
        }

        int expectedVersion = parseExpectedVersion(obj);
        if (!obj.hasNonNull("reason") || !obj.get("reason").isTextual()) {
            throw new DocumentBadRequestException("missing or invalid reason");
        }
        String reason = obj.get("reason").asText().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw new DocumentBadRequestException("reason must be 1 to 200 characters");
        }
        if (!isValidReason(reason)) {
            throw new DocumentBadRequestException("reason contains forbidden characters");
        }
        return new ParsedRejectRequest(expectedVersion, reason);
    }

    public ParsedDueDateRequest parseDueDateRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new DocumentBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion", "dueDate");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new DocumentBadRequestException("unknown property in request envelope");
            }
        }

        int expectedVersion = parseExpectedVersion(obj);
        LocalDate dueDate = null;
        if (obj.hasNonNull("dueDate")) {
            if (!obj.get("dueDate").isTextual()) {
                throw new DocumentBadRequestException("invalid dueDate");
            }
            try {
                dueDate = LocalDate.parse(obj.get("dueDate").asText().trim());
            } catch (Exception e) {
                throw new DocumentBadRequestException("invalid dueDate");
            }
        }
        return new ParsedDueDateRequest(expectedVersion, dueDate);
    }

    public ParsedCancelRequest parseCancelRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new DocumentBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("expectedVersion");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new DocumentBadRequestException("unknown property in request envelope");
            }
        }

        int expectedVersion = parseExpectedVersion(obj);
        return new ParsedCancelRequest(expectedVersion);
    }

    private int parseExpectedVersion(ObjectNode obj) {
        if (!obj.hasNonNull("expectedVersion") || !obj.get("expectedVersion").isIntegralNumber()) {
            throw new DocumentBadRequestException("missing or invalid expectedVersion");
        }
        int v = obj.get("expectedVersion").asInt();
        if (v < 1) {
            throw new DocumentBadRequestException("invalid expectedVersion");
        }
        return v;
    }

    private boolean isValidReason(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }
}
