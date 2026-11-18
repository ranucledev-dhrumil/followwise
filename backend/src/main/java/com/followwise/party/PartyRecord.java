package com.followwise.party;

import java.time.Instant;
import java.util.UUID;

public record PartyRecord(
        UUID id,
        UUID workspaceId,
        UUID appId,
        String partyType,
        String displayName,
        String dataJson,
        int definitionVersion,
        int version,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Instant remindersPausedAt
) {
    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isPaused() {
        return remindersPausedAt != null;
    }
}
