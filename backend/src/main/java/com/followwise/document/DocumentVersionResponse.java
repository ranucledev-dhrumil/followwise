package com.followwise.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionResponse(
        UUID id,
        UUID requestId,
        int versionNo,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant purgedAt
) {
    public static DocumentVersionResponse from(DocumentRecord r) {
        return new DocumentVersionResponse(
                r.id(),
                r.requestId(),
                r.versionNo(),
                r.originalFilename(),
                r.contentType(),
                r.sizeBytes(),
                r.sha256(),
                r.uploadedBy(),
                r.uploadedAt(),
                r.purgedAt()
        );
    }
}
