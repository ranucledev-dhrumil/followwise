package com.followwise.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentRecord(
        UUID id,
        UUID workspaceId,
        UUID appId,
        UUID partyId,
        UUID requestId,
        int versionNo,
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant purgedAt
) {
}
