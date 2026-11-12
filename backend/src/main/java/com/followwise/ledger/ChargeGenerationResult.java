package com.followwise.ledger;

import java.time.LocalDate;

public record ChargeGenerationResult(
        boolean dryRun,
        int created,
        int skippedExisting,
        int skippedInvalid,
        int skippedLocked,
        int failed,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate today
) {}
