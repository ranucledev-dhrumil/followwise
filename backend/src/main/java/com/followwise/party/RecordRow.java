package com.followwise.party;

import java.time.Instant;
import java.util.UUID;

public record RecordRow(
        UUID id,
        UUID workspaceId,
        UUID appId,
        String recordType,
        String displayName,
        String dataJson,
        int definitionVersion,
        int version,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
    public boolean isArchived() {
        return archivedAt != null;
    }
}
