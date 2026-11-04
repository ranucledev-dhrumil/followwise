package com.followwise.app;

import java.time.Instant;
import java.util.UUID;

public record AppDefinitionVersionRecord(
        UUID id,
        UUID appId,
        int version,
        String definition,
        String note,
        UUID createdBy,
        Instant createdAt
) {
}
