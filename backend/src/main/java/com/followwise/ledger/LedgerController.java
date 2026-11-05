package com.followwise.ledger;

import com.followwise.party.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}")
public class LedgerController {

    private final LedgerService ledgerService;
    private final LedgerPayloadParser ledgerPayloadParser;
    private final ChargeGeneratorService chargeGeneratorService;
    private final com.followwise.workspace.AccessService accessService;

    public LedgerController(
            LedgerService ledgerService,
            LedgerPayloadParser ledgerPayloadParser,
            ChargeGeneratorService chargeGeneratorService,
            com.followwise.workspace.AccessService accessService
    ) {
        this.ledgerService = ledgerService;
        this.ledgerPayloadParser = ledgerPayloadParser;
        this.chargeGeneratorService = chargeGeneratorService;
        this.accessService = accessService;
    }

    private UUID parseIdempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new LedgerBadRequestException("invalid Idempotency-Key format");
        }
    }

    @PostMapping("/parties/{partyId}/charges")
    public ResponseEntity<EntryResponse> createCharge(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);
        var parsed = ledgerPayloadParser.parseCreateChargeRequest(rawBody);
        EntryResponse resp = ledgerService.createCharge(
                workspaceId, appId, partyId, userId,
                parsed.label(), parsed.amountCents(), parsed.dueDate(), parsed.scheduleId(),
                idempotencyKey, request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/charges/{chargeId}/payments")
    public ResponseEntity<EntryResponse> recordPayment(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("chargeId") UUID chargeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);
        var parsed = ledgerPayloadParser.parseRecordPaymentRequest(rawBody);
        EntryResponse resp = ledgerService.recordPayment(
                workspaceId, appId, chargeId, userId,
                parsed.amountCents(), parsed.paidOn(), parsed.note(),
                idempotencyKey, request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/ledger/{entryId}/reverse")
    public ResponseEntity<EntryResponse> reverseEntry(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("entryId") UUID entryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);
        var parsed = ledgerPayloadParser.parseReverseEntryRequest(rawBody);
        EntryResponse resp = ledgerService.reverseEntry(
                workspaceId, appId, entryId, userId,
                parsed.reason(), idempotencyKey, request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/parties/{partyId}/ledger")
    public ResponseEntity<PartyLedgerResponse> getPartyLedger(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(ledgerService.getPartyLedger(workspaceId, appId, partyId, userId, page, size));
    }

    @GetMapping("/charges")
    public ResponseEntity<PageResponse<ChargeResponse>> listCharges(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "status", defaultValue = "all") String status,
            @RequestParam(value = "partyId", required = false) UUID partyId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(ledgerService.listCharges(workspaceId, appId, userId, status, partyId, page, size));
    }

    @GetMapping("/charges/{chargeId}")
    public ResponseEntity<ChargeResponse> getCharge(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("chargeId") UUID chargeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(ledgerService.getCharge(workspaceId, appId, chargeId, userId));
    }

    @PostMapping("/charges/generate")
    public ResponseEntity<ChargeGenerationResult> generateCharges(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "dryRun", defaultValue = "false") String dryRunParam,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (!"true".equalsIgnoreCase(dryRunParam) && !"false".equalsIgnoreCase(dryRunParam)) {
            throw new LedgerBadRequestException("dryRun must be 'true' or 'false'");
        }
        boolean dryRun = Boolean.parseBoolean(dryRunParam);
        UUID userId = UUID.fromString(jwt.getSubject());
        accessService.requirePermission(workspaceId, userId, com.followwise.workspace.Permission.MANAGE_LEDGER);

        ChargeGenerationResult result = chargeGeneratorService.generateForApp(
                workspaceId, appId, userId, dryRun, false
        );
        return ResponseEntity.ok(result);
    }
}
