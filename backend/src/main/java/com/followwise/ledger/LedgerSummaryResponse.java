package com.followwise.ledger;

public record LedgerSummaryResponse(
        long totalChargedCents,
        long totalPaidCents,
        long balanceCents
) {
}
