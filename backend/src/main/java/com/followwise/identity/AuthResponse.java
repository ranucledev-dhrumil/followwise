package com.followwise.identity;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        UUID userId,
        String email
) {}
