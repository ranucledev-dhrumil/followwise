package com.followwise.document;

import com.followwise.party.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentPayloadParser documentPayloadParser;

    public DocumentController(
            DocumentService documentService,
            DocumentPayloadParser documentPayloadParser
    ) {
        this.documentService = documentService;
        this.documentPayloadParser = documentPayloadParser;
    }

    @PostMapping("/parties/{partyId}/document-requests")
    public ResponseEntity<AssignChecklistResponse> assignChecklist(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var parsed = documentPayloadParser.parseAssignChecklistRequest(rawBody);
        AssignChecklistResponse resp = documentService.assignChecklist(
                workspaceId, appId, partyId, userId, parsed.checklistId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/parties/{partyId}/document-requests")
    public ResponseEntity<List<DocumentRequestResponse>> listPartyRequests(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("partyId") UUID partyId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(documentService.listPartyRequests(workspaceId, appId, partyId, userId));
    }

    @GetMapping("/document-requests")
    public ResponseEntity<PageResponse<DocumentRequestResponse>> listRequests(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "overdue", required = false) Boolean overdue,
            @RequestParam(value = "expiringWithinDays", required = false) Integer expiringWithinDays,
            @RequestParam(value = "partyId", required = false) UUID partyId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(documentService.listRequests(
                workspaceId, appId, userId, status, overdue, expiringWithinDays, partyId, page, size
        ));
    }

    @GetMapping("/document-requests/{requestId}")
    public ResponseEntity<DocumentRequestResponse> getRequest(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(documentService.getRequest(workspaceId, appId, requestId, userId));
    }

    @PostMapping("/document-requests/{requestId}/documents")
    public ResponseEntity<DocumentVersionResponse> uploadDocument(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @RequestHeader(value = "X-Filename", required = false) String xFilenameHeader,
            @RequestBody byte[] body,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UploadDocumentResult result = documentService.uploadDocument(
                workspaceId, appId, requestId, userId, body, xFilenameHeader
        );
        if (result.isDuplicate()) {
            return ResponseEntity.ok(result.document());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.document());
    }

    @GetMapping("/document-requests/{requestId}/documents")
    public ResponseEntity<List<DocumentVersionResponse>> listVersions(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(documentService.listVersions(workspaceId, appId, requestId, userId));
    }

    @PostMapping("/document-requests/{requestId}/approve")
    public ResponseEntity<DocumentRequestResponse> approveRequest(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var parsed = documentPayloadParser.parseApproveRequest(rawBody);
        DocumentRequestResponse resp = documentService.approveRequest(
                workspaceId, appId, requestId, userId, parsed.expectedVersion(), parsed.expiresOn()
        );
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/document-requests/{requestId}/reject")
    public ResponseEntity<DocumentRequestResponse> rejectRequest(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var parsed = documentPayloadParser.parseRejectRequest(rawBody);
        DocumentRequestResponse resp = documentService.rejectRequest(
                workspaceId, appId, requestId, userId, parsed.expectedVersion(), parsed.reason()
        );
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/document-requests/{requestId}/due-date")
    public ResponseEntity<DocumentRequestResponse> updateDueDate(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var parsed = documentPayloadParser.parseDueDateRequest(rawBody);
        DocumentRequestResponse resp = documentService.updateDueDate(
                workspaceId, appId, requestId, userId, parsed.expectedVersion(), parsed.dueDate()
        );
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/document-requests/{requestId}/cancel")
    public ResponseEntity<DocumentRequestResponse> cancelRequest(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("requestId") UUID requestId,
            @RequestBody byte[] rawBody,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var parsed = documentPayloadParser.parseCancelRequest(rawBody);
        DocumentRequestResponse resp = documentService.cancelRequest(
                workspaceId, appId, requestId, userId, parsed.expectedVersion()
        );
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> downloadDocument(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("documentId") UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        DocumentDownload download = documentService.getDocumentDownload(workspaceId, appId, documentId, userId);

        org.springframework.http.ContentDisposition cd = org.springframework.http.ContentDisposition.attachment()
                .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8)
                .build();

        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = outputStream -> {
            try (java.io.InputStream in = download.stream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .body(body);
    }

    @PostMapping("/documents/{documentId}/purge")
    public ResponseEntity<DocumentVersionResponse> purgeDocument(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @PathVariable("documentId") UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(documentService.purgeDocument(workspaceId, appId, documentId, userId));
    }
}
