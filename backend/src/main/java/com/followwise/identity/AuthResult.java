package com.followwise.identity;

import java.util.UUID;

public record AuthResult(
        String accessToken,
        String rawRefreshToken,
        UUID userId,
        String email
) {}
