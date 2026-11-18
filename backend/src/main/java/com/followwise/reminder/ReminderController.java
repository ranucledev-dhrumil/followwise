package com.followwise.reminder;

import com.followwise.app.AppBadRequestException;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppStore;
import com.followwise.app.AppValidationException;
import com.followwise.party.PartyResponse;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}")
public class ReminderController {

    private final ReminderEngineService reminderEngineService;
    private final ReminderService reminderService;
    private final ReminderEventStore reminderEventStore;
    private final AccessService accessService;
    private final AppStore appStore;
    private final AppPayloadParser appPayloadParser;

    public ReminderController(
            ReminderEngineService reminderEngineService,
            ReminderService reminderService,
            ReminderEventStore reminderEventStore,
            AccessService accessService,
            AppStore appStore,
            AppPayloadParser appPayloadParser
    ) {
        this.reminderEngineService = reminderEngineService;
        this.reminderService = reminderService;
        this.reminderEventStore = reminderEventStore;
        this.accessService = accessService;
        this.appStore = appStore;
        this.appPayloadParser = appPayloadParser;
    }

    @GetMapping("/reminders/preview")
    public ResponseEntity<ReminderPreviewResponse> preview(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_REMINDERS);

        var result = reminderEngineService.processApp(workspaceId, appId, true, false);
        return ResponseEntity.ok(new ReminderPreviewResponse(
                result.remindersEnabled(),
                result.blockedByBacklog(),
                result.capReached(),
                result.scanLimitReached(),
                result.previewItems()
        ));
    }

    @PostMapping("/parties/{partyId}/reminders/pause")
    public ResponseEntity<PartyResponse> pauseReminders(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        int expectedVersion = parseExpectedVersion(rawBody);
        PartyResponse response = reminderService.pauseReminders(workspaceId, appId, partyId, expectedVersion, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/parties/{partyId}/reminders/resume")
    public ResponseEntity<PartyResponse> resumeReminders(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        int expectedVersion = parseExpectedVersion(rawBody);
        PartyResponse response = reminderService.resumeReminders(workspaceId, appId, partyId, expectedVersion, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reminder-events")
    public ResponseEntity<List<ReminderEventResponse>> listHistory(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "partyId", required = false) UUID partyId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_REMINDERS);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new ReminderNotFoundException("App not found"));

        if (page < 0) {
            throw new ReminderBadRequestException("page must be >= 0");
        }
        if (size < 1 || size > 50) {
            throw new ReminderBadRequestException("size must be between 1 and 50");
        }

        List<ReminderEventStore.ReminderEventRecord> records =
                reminderEventStore.findHistory(workspaceId, appId, partyId, status, page, size);

        List<ReminderEventResponse> responses = records.stream()
                .map(r -> new ReminderEventResponse(
                        r.id(),
                        r.workspaceId(),
                        r.appId(),
                        r.partyId(),
                        r.ruleId(),
                        r.subjectId(),
                        r.recipientKind(),
                        r.recipientUserId(),
                        r.scheduledFor(),
                        r.status(),
                        r.skipReason(),
                        r.outboxId(),
                        r.messageKey(),
                        r.createdAt()
                ))
                .toList();

        return ResponseEntity.ok(responses);
    }

    private int parseExpectedVersion(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ReminderBadRequestException("missing payload");
        }
        JsonNode root;
        try {
            root = appPayloadParser.parseStrictJson(rawBody);
        } catch (AppBadRequestException e) {
            String msg = e.getMessage();
            if ("missing payload".equals(msg)
                    || "maximum nesting depth exceeded".equals(msg)
                    || "invalid JSON syntax".equals(msg)
                    || "request body must be a JSON object".equals(msg)
                    || "unknown property in request envelope".equals(msg)) {
                throw new ReminderBadRequestException(msg);
            }
            throw new ReminderBadRequestException("invalid request payload");
        } catch (AppValidationException e) {
            throw new ReminderBadRequestException("invalid request payload");
        }
        if (!root.isObject()) {
            throw new ReminderBadRequestException("request body must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        for (var entry : obj.properties()) {
            if (!"expectedVersion".equals(entry.getKey())) {
                throw new ReminderBadRequestException("unknown property in request envelope");
            }
        }
        if (!obj.hasNonNull("expectedVersion") || !obj.get("expectedVersion").isIntegralNumber()) {
            throw new ReminderBadRequestException("missing or invalid expectedVersion");
        }
        int v = obj.get("expectedVersion").asInt();
        if (v < 1) {
            throw new ReminderBadRequestException("invalid expectedVersion");
        }
        return v;
    }
}
