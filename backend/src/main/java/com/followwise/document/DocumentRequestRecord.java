package com.followwise.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DocumentRequestRecord(
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
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt
) {
}
