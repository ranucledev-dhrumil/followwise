package com.followwise.ledger;

import com.followwise.app.AppBadRequestException;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Set;

@Component
public class LedgerPayloadParser {

    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final AppPayloadParser appPayloadParser;

    public record ParsedCreateChargeRequest(
            String label,
            long amountCents,
            LocalDate dueDate,
            String scheduleId
    ) {}

    public record ParsedRecordPaymentRequest(
            long amountCents,
            LocalDate paidOn,
            String note
    ) {}

    public record ParsedReverseEntryRequest(
            String reason
    ) {}

    public LedgerPayloadParser(AppPayloadParser appPayloadParser) {
        this.appPayloadParser = appPayloadParser;
    }

    public JsonNode parseStrictJson(byte[] rawBody) {
        try {
            return appPayloadParser.parseStrictJson(rawBody);
        } catch (AppValidationException e) {
            throw new LedgerValidationException(e.getErrors());
        } catch (AppBadRequestException e) {
            throw new LedgerBadRequestException(e.getMessage());
        }
    }

    public ParsedCreateChargeRequest parseCreateChargeRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new LedgerBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("label", "amountCents", "dueDate", "scheduleId");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new LedgerBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("label") || !obj.get("label").isTextual()) {
            throw new LedgerBadRequestException("missing or invalid label");
        }
        String label = obj.get("label").asText();

        if (!obj.hasNonNull("amountCents")) {
            throw new LedgerBadRequestException("missing amountCents");
        }
        JsonNode amountNode = obj.get("amountCents");
        if (amountNode.isFloatingPointNumber() || !amountNode.isIntegralNumber() || !amountNode.canConvertToLong()) {
            throw new LedgerBadRequestException("invalid amountCents: must be an integer");
        }
        long amountCents = amountNode.asLong();

        if (!obj.hasNonNull("dueDate") || !obj.get("dueDate").isTextual()) {
            throw new LedgerBadRequestException("missing or invalid dueDate");
        }
        LocalDate dueDate;
        try {
            dueDate = LocalDate.parse(obj.get("dueDate").asText().trim(), STRICT_DATE);
        } catch (Exception e) {
            throw new LedgerBadRequestException("invalid dueDate format");
        }

        String scheduleId = null;
        if (obj.has("scheduleId")) {
            if (obj.get("scheduleId").isNull()) {
                scheduleId = null;
            } else if (obj.get("scheduleId").isTextual()) {
                scheduleId = obj.get("scheduleId").asText().trim();
            } else {
                throw new LedgerBadRequestException("invalid scheduleId");
            }
        }

        return new ParsedCreateChargeRequest(label, amountCents, dueDate, scheduleId);
    }

    public ParsedRecordPaymentRequest parseRecordPaymentRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new LedgerBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("amountCents", "paidOn", "note");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new LedgerBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("amountCents")) {
            throw new LedgerBadRequestException("missing amountCents");
        }
        JsonNode amountNode = obj.get("amountCents");
        if (amountNode.isFloatingPointNumber() || !amountNode.isIntegralNumber() || !amountNode.canConvertToLong()) {
            throw new LedgerBadRequestException("invalid amountCents: must be an integer");
        }
        long amountCents = amountNode.asLong();

        if (!obj.hasNonNull("paidOn") || !obj.get("paidOn").isTextual()) {
            throw new LedgerBadRequestException("missing or invalid paidOn");
        }
        LocalDate paidOn;
        try {
            paidOn = LocalDate.parse(obj.get("paidOn").asText().trim(), STRICT_DATE);
        } catch (Exception e) {
            throw new LedgerBadRequestException("invalid paidOn format");
        }

        String note = null;
        if (obj.has("note")) {
            if (obj.get("note").isNull()) {
                note = null;
            } else if (obj.get("note").isTextual()) {
                note = obj.get("note").asText();
            } else {
                throw new LedgerBadRequestException("invalid note");
            }
        }

        return new ParsedRecordPaymentRequest(amountCents, paidOn, note);
    }

    public ParsedReverseEntryRequest parseReverseEntryRequest(byte[] rawBody) {
        JsonNode root = parseStrictJson(rawBody);
        if (!root.isObject()) {
            throw new LedgerBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        Set<String> allowed = Set.of("reason");
        for (var entry : obj.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new LedgerBadRequestException("unknown property in request envelope");
            }
        }

        if (!obj.hasNonNull("reason") || !obj.get("reason").isTextual()) {
            throw new LedgerBadRequestException("missing or invalid reason");
        }
        String reason = obj.get("reason").asText();

        return new ParsedReverseEntryRequest(reason);
    }
}
