package com.followwise.party;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}/parties")
public class PartyController {

    private final PartyService partyService;
    private final PartyPayloadParser partyPayloadParser;

    public PartyController(PartyService partyService, PartyPayloadParser partyPayloadParser) {
        this.partyService = partyService;
        this.partyPayloadParser = partyPayloadParser;
    }

    @PostMapping
    public ResponseEntity<PartyResponse> createParty(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PartyPayloadParser.ParsedCreateRequest parsed = partyPayloadParser.parseCreateRequest(rawBody);
        PartyResponse response = partyService.createParty(workspaceId, appId, userId, parsed.type(), parsed.data());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<PartyResponse>> listParties(
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
        return ResponseEntity.ok(partyService.listParties(workspaceId, appId, userId, type, q, archived, page, size));
    }

    @GetMapping("/{partyId}")
    public ResponseEntity<PartyResponse> getParty(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(partyService.getParty(workspaceId, appId, partyId, userId));
    }

    @PutMapping("/{partyId}")
    public ResponseEntity<PartyResponse> updateParty(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PartyPayloadParser.ParsedUpdateRequest parsed = partyPayloadParser.parseUpdateRequest(rawBody);
        return ResponseEntity.ok(partyService.updateParty(workspaceId, appId, partyId, userId, parsed.expectedVersion(), parsed.data()));
    }

    @PostMapping("/{partyId}/archive")
    public ResponseEntity<Void> archiveParty(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        partyService.archiveParty(workspaceId, appId, partyId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{partyId}/restore")
    public ResponseEntity<Void> restoreParty(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        partyService.restoreParty(workspaceId, appId, partyId, userId);
        return ResponseEntity.ok().build();
    }
}
