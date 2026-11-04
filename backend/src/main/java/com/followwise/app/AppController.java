package com.followwise.app;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps")
public class AppController {

    private final AppService appService;
    private final AppPayloadParser appPayloadParser;

    public AppController(AppService appService, AppPayloadParser appPayloadParser) {
        this.appService = appService;
        this.appPayloadParser = appPayloadParser;
    }

    @PostMapping
    public ResponseEntity<AppResponse> createApp(
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AppPayloadParser.ParsedCreateAppRequest parsed = appPayloadParser.parseCreateAppRequest(rawBody);
        AppResponse response = appService.createApp(workspaceId, userId, parsed);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AppSummaryResponse>> listApps(
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appService.listApps(workspaceId, userId, archived));
    }

    @GetMapping("/{appId}")
    public ResponseEntity<AppResponse> getApp(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appService.getApp(workspaceId, appId, userId));
    }

    @GetMapping("/{appId}/versions")
    public ResponseEntity<List<AppVersionSummaryResponse>> listVersions(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appService.listVersions(workspaceId, appId, userId));
    }

    @GetMapping("/{appId}/versions/{version}")
    public ResponseEntity<AppVersionResponse> getVersion(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("version") int version,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appService.getVersion(workspaceId, appId, version, userId));
    }

    @PutMapping("/{appId}/definition")
    public ResponseEntity<AppResponse> putDefinition(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AppPayloadParser.ParsedPutDefinitionRequest parsed = appPayloadParser.parsePutDefinitionRequest(rawBody);
        return ResponseEntity.ok(appService.putDefinition(workspaceId, appId, userId, parsed));
    }

    @PostMapping("/{appId}/archive")
    public ResponseEntity<Void> archiveApp(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        appService.archiveApp(workspaceId, appId, userId);
        return ResponseEntity.ok().build();
    }
}
