package com.followwise.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EntryResponse(
        UUID id,
        UUID workspaceId,
        UUID appId,
        UUID partyId,
        UUID chargeId,
        String entryType,
        long amountCents,
        String currency,
        String kind,
        String scheduleId,
        String periodKey,
        String label,
        LocalDate dueDate,
        LocalDate paidOn,
        String note,
        UUID reversesEntryId,
        Instant createdAt
) {
    public static EntryResponse from(LedgerEntry entry) {
        return new EntryResponse(
                entry.id(),
                entry.workspaceId(),
                entry.appId(),
                entry.partyId(),
                entry.chargeId(),
                entry.entryType(),
                entry.amountCents(),
                entry.currency(),
                entry.kind(),
                entry.scheduleId(),
                entry.periodKey(),
                entry.label(),
                entry.dueDate(),
                entry.paidOn(),
                entry.note(),
                entry.reversesEntryId(),
                entry.createdAt()
        );
    }
}
