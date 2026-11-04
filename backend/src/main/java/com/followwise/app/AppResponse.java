package com.followwise.app;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AppResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String templateKey,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        JsonNode definition
) {
}
