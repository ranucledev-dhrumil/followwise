package com.followwise.workspace;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        UUID userId,
        String email,
        Role role,
        Instant createdAt
) {}
