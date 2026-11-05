package com.followwise.ledger;

import com.followwise.app.AppDefinitionVersionRecord;
import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.appdef.ValidationError;
import com.followwise.party.PageResponse;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyStore;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class LedgerService {

    private final LedgerStore ledgerStore;
    private final PartyStore partyStore;
    private final AppStore appStore;
    private final AccessService accessService;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final int maxLedgerEntriesPerApp;

    public LedgerService(
            LedgerStore ledgerStore,
            PartyStore partyStore,
            AppStore appStore,
            AccessService accessService,
            Clock clock,
            @Value("${app.max-ledger-entries-per-app:20000}") int maxLedgerEntriesPerApp
    ) {
        this.ledgerStore = ledgerStore;
        this.partyStore = partyStore;
        this.appStore = appStore;
        this.accessService = accessService;
        this.clock = clock;
        this.jsonMapper = JsonMapper.builder().build();
        this.maxLedgerEntriesPerApp = maxLedgerEntriesPerApp;
    }

    private LocalDate getToday(JsonNode defNode) {
        ZoneId zoneId;
        try {
            String tz = defNode.path("timezone").asText(null);
            zoneId = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("UTC");
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }
        return LocalDate.now(clock.withZone(zoneId));
    }

    private JsonNode loadDefinition(UUID appId, int version) {
        AppDefinitionVersionRecord ver = appStore.findVersion(appId, version)
                .orElseThrow(() -> new LedgerNotFoundException("Current version not found"));
        try {
            return jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }
    }

    public static String computeRequestHash(UUID userId, String method, String path, String canonicalPayload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = userId + "|" + method + "|" + path + "|" + canonicalPayload;
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isValidString(String s, int minLen, int maxLen) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < minLen || trimmed.length() > maxLen) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    @Transactional
    public EntryResponse createCharge(
            UUID workspaceId, UUID appId, UUID partyId, UUID userId,
            String label, long amountCents, LocalDate dueDate, String scheduleId,
            UUID idempotencyKey, String requestPath
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_LEDGER);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new LedgerConflictException("app_archived", "App is archived");
        }

        PartyRecord party = partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new LedgerNotFoundException("Party not found"));

        // Locks: app row, then party row
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new LedgerConflictException("app_archived", "App is archived or not found");
        }

        if (ledgerStore.countTotal(workspaceId, appId) >= maxLedgerEntriesPerApp) {
            throw new LedgerConflictException("limit_exceeded", "Maximum ledger entries limit exceeded");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, partyId).isEmpty()) {
            throw new LedgerNotFoundException("Party not found");
        }

        String canonical = label + "|" + amountCents + "|" + dueDate + "|" + (scheduleId != null ? scheduleId : "");
        String requestHash = computeRequestHash(userId, "POST", requestPath, canonical);

        if (idempotencyKey != null) {
            Optional<LedgerEntry> existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey);
            if (existing.isPresent()) {
                LedgerEntry e = existing.get();
                if (requestHash.equals(e.requestHash()) && userId.equals(e.createdBy())) {
                    return EntryResponse.from(e);
                } else {
                    throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
                }
            }
        }

        if (party.isArchived()) {
            throw new LedgerConflictException("party_archived", "Cannot create charge for an archived party");
        }

        JsonNode defNode = loadDefinition(appId, app.currentVersion());
        LocalDate today = getToday(defNode);

        List<ValidationError> errors = new ArrayList<>();
        if (!isValidString(label, 1, 120)) {
            errors.add(new ValidationError("/label", "invalid label"));
        }
        if (amountCents <= 0 || amountCents > 100_000_000_000L) {
            errors.add(new ValidationError("/amountCents", "invalid amount"));
        }
        if (dueDate == null || dueDate.isBefore(today.minusYears(5)) || dueDate.isAfter(today.plusYears(5))) {
            errors.add(new ValidationError("/dueDate", "due date out of range"));
        }
        if (scheduleId != null) {
            boolean matched = false;
            JsonNode schedules = defNode.path("chargeSchedules");
            if (schedules.isArray()) {
                for (JsonNode s : schedules) {
                    if (scheduleId.equals(s.path("id").asText(null))) {
                        boolean archived = s.path("archived").asBoolean(false);
                        String pt = s.path("partyType").asText(null);
                        if (!archived && party.partyType().equals(pt)) {
                            matched = true;
                        }
                        break;
                    }
                }
            }
            if (!matched) {
                errors.add(new ValidationError("/scheduleId", "invalid charge schedule"));
            }
        }

        if (!errors.isEmpty()) {
            throw new LedgerValidationException(errors);
        }

        UUID chargeId = UUID.randomUUID();
        try {
            Instant createdAt = ledgerStore.insertEntry(
                    chargeId, workspaceId, appId, partyId, chargeId,
                    "CHARGE", amountCents, "USD", "manual",
                    scheduleId, null, label, dueDate, null, null, null,
                    idempotencyKey, requestHash, userId
            );

            return new EntryResponse(
                    chargeId, workspaceId, appId, partyId, chargeId,
                    "CHARGE", amountCents, "USD", "manual",
                    scheduleId, null, label, dueDate, null, null, null,
                    createdAt
            );
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null) {
                LedgerEntry existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey)
                        .orElseThrow(() -> e);
                if (requestHash.equals(existing.requestHash()) && userId.equals(existing.createdBy())) {
                    return EntryResponse.from(existing);
                }
                throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
            }
            throw e;
        }
    }

    @Transactional
    public EntryResponse recordPayment(
            UUID workspaceId, UUID appId, UUID chargeId, UUID userId,
            long amountCents, LocalDate paidOn, String note,
            UUID idempotencyKey, String requestPath
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.RECORD_PAYMENT);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new LedgerConflictException("app_archived", "App is archived");
        }

        LedgerEntry charge = ledgerStore.findChargeById(workspaceId, appId, chargeId)
                .orElseThrow(() -> new LedgerNotFoundException("Charge not found"));

        // Locks: app row, then charge row
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new LedgerConflictException("app_archived", "App is archived or not found");
        }

        if (ledgerStore.countTotal(workspaceId, appId) >= maxLedgerEntriesPerApp) {
            throw new LedgerConflictException("limit_exceeded", "Maximum ledger entries limit exceeded");
        }

        if (ledgerStore.lockChargeRow(workspaceId, appId, chargeId).isEmpty()) {
            throw new LedgerNotFoundException("Charge not found");
        }

        String canonical = amountCents + "|" + paidOn + "|" + (note != null ? note : "");
        String requestHash = computeRequestHash(userId, "POST", requestPath, canonical);

        if (idempotencyKey != null) {
            Optional<LedgerEntry> existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey);
            if (existing.isPresent()) {
                LedgerEntry e = existing.get();
                if (requestHash.equals(e.requestHash()) && userId.equals(e.createdBy())) {
                    return EntryResponse.from(e);
                } else {
                    throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
                }
            }
        }

        if (ledgerStore.isChargeReversed(workspaceId, appId, chargeId)) {
            throw new LedgerConflictException("charge_reversed", "Cannot make payment on reversed charge");
        }

        JsonNode defNode = loadDefinition(appId, app.currentVersion());
        LocalDate today = getToday(defNode);

        List<ValidationError> errors = new ArrayList<>();
        if (amountCents <= 0 || amountCents > 100_000_000_000L) {
            errors.add(new ValidationError("/amountCents", "invalid amount"));
        }
        if (paidOn == null || paidOn.isAfter(today) || paidOn.isBefore(LocalDate.of(2000, 1, 1))) {
            errors.add(new ValidationError("/paidOn", "invalid payment date"));
        }
        if (note != null && !note.isEmpty() && !isValidString(note, 1, 200)) {
            errors.add(new ValidationError("/note", "invalid note"));
        }

        if (!errors.isEmpty()) {
            throw new LedgerValidationException(errors);
        }

        long outstanding = ledgerStore.computeOutstanding(workspaceId, appId, chargeId);
        if (amountCents > outstanding) {
            throw new LedgerConflictException("payment_exceeds_outstanding", "Payment amount exceeds charge outstanding balance");
        }

        UUID paymentId = UUID.randomUUID();
        try {
            Instant createdAt = ledgerStore.insertEntry(
                    paymentId, workspaceId, appId, charge.partyId(), chargeId,
                    "PAYMENT", -amountCents, "USD", null,
                    null, null, null, null, paidOn, note, null,
                    idempotencyKey, requestHash, userId
            );

            return new EntryResponse(
                    paymentId, workspaceId, appId, charge.partyId(), chargeId,
                    "PAYMENT", -amountCents, "USD", null,
                    null, null, null, null, paidOn, note, null,
                    createdAt
            );
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null) {
                LedgerEntry existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey)
                        .orElseThrow(() -> e);
                if (requestHash.equals(existing.requestHash()) && userId.equals(existing.createdBy())) {
                    return EntryResponse.from(existing);
                }
                throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
            }
            throw e;
        }
    }

    @Transactional
    public EntryResponse reverseEntry(
            UUID workspaceId, UUID appId, UUID entryId, UUID userId,
            String reason, UUID idempotencyKey, String requestPath
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_LEDGER);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new LedgerConflictException("app_archived", "App is archived");
        }

        LedgerEntry targetEntry = ledgerStore.findEntryById(workspaceId, appId, entryId)
                .orElseThrow(() -> new LedgerNotFoundException("Entry not found"));

        // Locks: app row, then charge row
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new LedgerConflictException("app_archived", "App is archived or not found");
        }

        if (ledgerStore.countTotal(workspaceId, appId) >= maxLedgerEntriesPerApp) {
            throw new LedgerConflictException("limit_exceeded", "Maximum ledger entries limit exceeded");
        }

        if (ledgerStore.lockChargeRow(workspaceId, appId, targetEntry.chargeId()).isEmpty()) {
            throw new LedgerNotFoundException("Charge not found");
        }

        String canonical = reason != null ? reason : "";
        String requestHash = computeRequestHash(userId, "POST", requestPath, canonical);

        if (idempotencyKey != null) {
            Optional<LedgerEntry> existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey);
            if (existing.isPresent()) {
                LedgerEntry e = existing.get();
                if (requestHash.equals(e.requestHash()) && userId.equals(e.createdBy())) {
                    return EntryResponse.from(e);
                } else {
                    throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
                }
            }
        }

        if (targetEntry.isReversal()) {
            throw new LedgerConflictException("cannot_reverse_reversal", "Cannot reverse a reversal entry");
        }

        List<ValidationError> errors = new ArrayList<>();
        if (!isValidString(reason, 1, 200)) {
            errors.add(new ValidationError("/reason", "invalid reversal reason"));
        }
        if (!errors.isEmpty()) {
            throw new LedgerValidationException(errors);
        }

        if (targetEntry.isCharge()) {
            if (ledgerStore.isChargeReversed(workspaceId, appId, targetEntry.id())) {
                throw new LedgerConflictException("already_reversed", "Charge is already reversed");
            }
            long outstanding = ledgerStore.computeOutstanding(workspaceId, appId, targetEntry.id());
            if (outstanding != targetEntry.amountCents()) {
                throw new LedgerConflictException("charge_has_payments", "Cannot reverse a charge with active payments");
            }
        } else if (targetEntry.isPayment()) {
            boolean alreadyReversed = ledgerStore.findEntriesByChargeId(workspaceId, appId, targetEntry.chargeId())
                    .stream()
                    .anyMatch(e -> targetEntry.id().equals(e.reversesEntryId()));
            if (alreadyReversed) {
                throw new LedgerConflictException("already_reversed", "Payment is already reversed");
            }
        }

        UUID reversalId = UUID.randomUUID();
        long reversalAmount = -targetEntry.amountCents();

        try {
            Instant createdAt = ledgerStore.insertEntry(
                    reversalId, workspaceId, appId, targetEntry.partyId(), targetEntry.chargeId(),
                    "REVERSAL", reversalAmount, "USD", null,
                    null, null, null, null, null, reason, targetEntry.id(),
                    idempotencyKey, requestHash, userId
            );

            return new EntryResponse(
                    reversalId, workspaceId, appId, targetEntry.partyId(), targetEntry.chargeId(),
                    "REVERSAL", reversalAmount, "USD", null,
                    null, null, null, null, null, reason, targetEntry.id(),
                    createdAt
            );
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null) {
                LedgerEntry existing = ledgerStore.findByIdempotencyKey(appId, idempotencyKey)
                        .orElseThrow(() -> e);
                if (requestHash.equals(existing.requestHash()) && userId.equals(existing.createdBy())) {
                    return EntryResponse.from(existing);
                }
                throw new LedgerConflictException("idempotency_conflict", "Idempotency key previously used with different parameters");
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public PartyLedgerResponse getPartyLedger(
            UUID workspaceId, UUID appId, UUID partyId, UUID userId, int page, int size
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_LEDGER);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new LedgerNotFoundException("Party not found"));

        if (page < 0) {
            throw new LedgerBadRequestException("page must be >= 0");
        }
        if (size <= 0) {
            size = 20;
        } else if (size > 50) {
            size = 50;
        }

        List<LedgerEntry> entries = ledgerStore.findByParty(workspaceId, appId, partyId, page, size);
        long total = ledgerStore.countByParty(workspaceId, appId, partyId);
        LedgerSummaryResponse summary = ledgerStore.computePartySummary(workspaceId, appId, partyId);

        List<EntryResponse> items = entries.stream().map(EntryResponse::from).toList();
        return new PartyLedgerResponse(items, page, size, total, summary);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChargeResponse> listCharges(
            UUID workspaceId, UUID appId, UUID userId, String status, UUID partyId, int page, int size
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_LEDGER);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        if (partyId != null) {
            partyStore.findById(workspaceId, appId, partyId)
                    .orElseThrow(() -> new LedgerNotFoundException("Party not found"));
        }

        if (page < 0) {
            throw new LedgerBadRequestException("page must be >= 0");
        }
        if (size <= 0) {
            size = 20;
        } else if (size > 50) {
            size = 50;
        }

        JsonNode defNode = loadDefinition(appId, app.currentVersion());
        LocalDate today = getToday(defNode);

        List<LedgerStore.ChargeSummaryRecord> summaries = ledgerStore.findChargesPage(
                workspaceId, appId, partyId, status, today, page, size
        );
        long total = ledgerStore.countChargesFiltered(
                workspaceId, appId, partyId, status, today
        );

        List<ChargeResponse> items = summaries.stream().map(s -> {
            LedgerEntry c = s.chargeEntry();
            return new ChargeResponse(
                    c.id(), c.workspaceId(), c.appId(), c.partyId(),
                    c.kind(), c.scheduleId(), c.periodKey(), c.label(),
                    c.amountCents(), s.outstandingCents(), c.currency(),
                    c.dueDate(), s.status(), c.createdAt(), null
            );
        }).toList();

        return new PageResponse<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ChargeResponse getCharge(UUID workspaceId, UUID appId, UUID chargeId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_LEDGER);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        LedgerEntry charge = ledgerStore.findChargeById(workspaceId, appId, chargeId)
                .orElseThrow(() -> new LedgerNotFoundException("Charge not found"));

        JsonNode defNode = loadDefinition(appId, app.currentVersion());
        LocalDate today = getToday(defNode);

        long outstanding = ledgerStore.computeOutstanding(workspaceId, appId, chargeId);
        boolean isReversed = ledgerStore.isChargeReversed(workspaceId, appId, chargeId);

        String status;
        if (isReversed) {
            status = "reversed";
        } else if (outstanding == 0) {
            status = "paid";
        } else if (charge.dueDate().isBefore(today)) {
            status = "overdue";
        } else {
            status = "open";
        }

        List<LedgerEntry> entries = ledgerStore.findEntriesByChargeId(workspaceId, appId, chargeId);
        List<EntryResponse> entryResponses = entries.stream().map(EntryResponse::from).toList();

        return new ChargeResponse(
                charge.id(), charge.workspaceId(), charge.appId(), charge.partyId(),
                charge.kind(), charge.scheduleId(), charge.periodKey(), charge.label(),
                charge.amountCents(), outstanding, charge.currency(),
                charge.dueDate(), status, charge.createdAt(), entryResponses
        );
    }
}
