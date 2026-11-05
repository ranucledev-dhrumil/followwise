package com.followwise.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ChargeResponse(
        UUID id,
        UUID workspaceId,
        UUID appId,
        UUID partyId,
        String kind,
        String scheduleId,
        String periodKey,
        String label,
        long amountCents,
        long outstandingCents,
        String currency,
        LocalDate dueDate,
        String status,
        Instant createdAt,
        List<EntryResponse> entries
) {
}
