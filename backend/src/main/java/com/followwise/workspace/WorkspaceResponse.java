package com.followwise.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String name,
        Role role,
        Instant createdAt
) {}
