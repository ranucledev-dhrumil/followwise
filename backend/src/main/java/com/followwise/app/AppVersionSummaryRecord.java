package com.followwise.app;

import java.time.Instant;
import java.util.UUID;

public record AppVersionSummaryRecord(
        UUID id,
        UUID appId,
        int version,
        String note,
        UUID createdBy,
        Instant createdAt
) {
}
