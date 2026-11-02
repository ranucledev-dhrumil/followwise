package com.followwise.authtoken;

import com.followwise.security.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthTokenService {

    public static final String TYPE_VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String TYPE_RESET_PASSWORD = "RESET_PASSWORD";

    private final AuthTokenRepository authTokenRepository;
    private final TokenService tokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthTokenService(AuthTokenRepository authTokenRepository, TokenService tokenService) {
        this.authTokenRepository = authTokenRepository;
        this.tokenService = tokenService;
    }

    public String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String rawToken) {
        return tokenService.hashToken(rawToken);
    }

    @Transactional
    public IssuedToken issueToken(UUID userId, String type) {
        Instant now = Instant.now();
        // Invalidate older unused tokens of the same type for this user
        authTokenRepository.invalidateUnusedTokens(userId, type, now);

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Duration ttl = TYPE_VERIFY_EMAIL.equals(type) ? Duration.ofHours(24) : Duration.ofHours(1);
        Instant expiresAt = now.plus(ttl);

        AuthToken token = new AuthToken(
                UUID.randomUUID(),
                userId,
                type,
                tokenHash,
                expiresAt,
                now
        );
        authTokenRepository.save(token);

        return new IssuedToken(rawToken, token);
    }

    @Transactional
    public Optional<AuthToken> consumeToken(String rawToken, String expectedType) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        String tokenHash = hashToken(rawToken);

        int updated = authTokenRepository.markUsedIfValid(tokenHash, expectedType, now);
        if (updated == 1) {
            return authTokenRepository.findByTokenHash(tokenHash);
        }
        return Optional.empty();
    }

    public long countTokensSince(UUID userId, String type, Instant since) {
        return authTokenRepository.countCreatedSince(userId, type, since);
    }
}
