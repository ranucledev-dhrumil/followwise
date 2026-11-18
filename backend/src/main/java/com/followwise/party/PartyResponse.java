package com.followwise.party;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PartyResponse(
        UUID id,
        String type,
        String displayName,
        JsonNode data,
        int version,
        int definitionVersion,
        boolean archived,
        boolean remindersPaused,
        Instant createdAt,
        Instant updatedAt
) {
}
