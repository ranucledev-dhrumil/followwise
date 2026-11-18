package com.followwise.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReminderEventResponse(
        UUID id,
        UUID workspaceId,
        UUID appId,
        UUID partyId,
        String ruleId,
        UUID subjectId,
        String recipientKind,
        UUID recipientUserId,
        LocalDate scheduledFor,
        String status,
        String skipReason,
        UUID outboxId,
        String messageKey,
        Instant createdAt
) {}
