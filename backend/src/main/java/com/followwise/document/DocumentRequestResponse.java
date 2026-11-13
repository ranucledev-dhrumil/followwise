package com.followwise.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentRequestResponse(
        UUID id,
        UUID workspaceId,
        UUID appId,
        UUID partyId,
        String checklistId,
        String itemId,
        String label,
        boolean required,
        Integer expiryDays,
        LocalDate dueDate,
        String status,
        String statusReason,
        UUID currentDocumentId,
        Instant approvedAt,
        LocalDate expiresOn,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt,
        List<DocumentVersionResponse> versions
) {
    public static DocumentRequestResponse from(DocumentRequestRecord r) {
        return from(r, null);
    }

    public static DocumentRequestResponse from(DocumentRequestRecord r, List<DocumentVersionResponse> versions) {
        return new DocumentRequestResponse(
                r.id(),
                r.workspaceId(),
                r.appId(),
                r.partyId(),
                r.checklistId(),
                r.itemId(),
                r.label(),
                r.required(),
                r.expiryDays(),
                r.dueDate(),
                r.status(),
                r.statusReason(),
                r.currentDocumentId(),
                r.approvedAt(),
                r.expiresOn(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.cancelledAt(),
                versions
        );
    }
}
