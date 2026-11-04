package com.followwise.app;

import java.time.Instant;
import java.util.UUID;

public record AppRecord(
        UUID id,
        UUID workspaceId,
        String name,
        String templateKey,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
    public boolean isArchived() {
        return archivedAt != null;
    }
}
