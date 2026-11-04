package com.followwise.app;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AppVersionResponse(
        UUID id,
        UUID appId,
        int version,
        String note,
        UUID createdBy,
        Instant createdAt,
        JsonNode definition
) {
}
