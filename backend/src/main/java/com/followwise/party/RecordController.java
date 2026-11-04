package com.followwise.party;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}/records")
public class RecordController {

    private final RecordService recordService;
    private final PartyPayloadParser partyPayloadParser;

    public RecordController(RecordService recordService, PartyPayloadParser partyPayloadParser) {
        this.recordService = recordService;
        this.partyPayloadParser = partyPayloadParser;
    }

    @PostMapping
    public ResponseEntity<RecordResponse> createRecord(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PartyPayloadParser.ParsedCreateRequest parsed = partyPayloadParser.parseCreateRequest(rawBody);
        RecordResponse response = recordService.createRecord(workspaceId, appId, userId, parsed.type(), parsed.data());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<RecordResponse>> listRecords(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(recordService.listRecords(workspaceId, appId, userId, type, q, archived, page, size));
    }

    @GetMapping("/{recordId}")
    public ResponseEntity<RecordResponse> getRecord(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("recordId") UUID recordId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(recordService.getRecord(workspaceId, appId, recordId, userId));
    }

    @PutMapping("/{recordId}")
    public ResponseEntity<RecordResponse> updateRecord(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("recordId") UUID recordId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PartyPayloadParser.ParsedUpdateRequest parsed = partyPayloadParser.parseUpdateRequest(rawBody);
        return ResponseEntity.ok(recordService.updateRecord(workspaceId, appId, recordId, userId, parsed.expectedVersion(), parsed.data()));
    }

    @PostMapping("/{recordId}/archive")
    public ResponseEntity<Void> archiveRecord(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("recordId") UUID recordId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        recordService.archiveRecord(workspaceId, appId, recordId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{recordId}/restore")
    public ResponseEntity<Void> restoreRecord(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("recordId") UUID recordId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        recordService.restoreRecord(workspaceId, appId, recordId, userId);
        return ResponseEntity.ok().build();
    }
}
