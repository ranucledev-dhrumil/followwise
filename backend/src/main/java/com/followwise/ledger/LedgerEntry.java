package com.followwise.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LedgerEntry(
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
        UUID idempotencyKey,
        String requestHash,
        UUID createdBy,
        Instant createdAt
) {
    public boolean isCharge() {
        return "CHARGE".equals(entryType);
    }

    public boolean isPayment() {
        return "PAYMENT".equals(entryType);
    }

    public boolean isReversal() {
        return "REVERSAL".equals(entryType);
    }
}
