package com.followwise.app;

import java.time.Instant;
import java.util.UUID;

public record AppSummaryResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String templateKey,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
}
