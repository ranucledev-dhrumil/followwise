package com.followwise.ledger;

import java.util.List;

public record PartyLedgerResponse(
        List<EntryResponse> items,
        int page,
        int size,
        long total,
        LedgerSummaryResponse summary
) {
}
