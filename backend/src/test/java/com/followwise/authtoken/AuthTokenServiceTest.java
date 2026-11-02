package com.followwise.authtoken;

import com.followwise.AbstractIntegrationTest;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void cleanUp() {
        authTokenRepository.deleteAll();
        userRepository.deleteAll();

        Instant now = Instant.now();
        testUser = new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com", "hash", now);
        userRepository.save(testUser);
    }

    @Test
    void tokenHashingAndExpiryDurations() {
        UUID userId = testUser.getId();
        Instant before = Instant.now();

        IssuedToken verifyToken = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(verifyToken.rawToken()).isNotBlank();
        assertThat(verifyToken.authToken().getTokenHash()).hasSize(64);
        assertThat(authTokenService.hashToken(verifyToken.rawToken()))
                .isEqualTo(verifyToken.authToken().getTokenHash());
        // Verify 24h expiry
        Duration verifyDuration = Duration.between(before, verifyToken.authToken().getExpiresAt());
        assertThat(verifyDuration.toHours()).isEqualTo(24);

        IssuedToken resetToken = authTokenService.issueToken(userId, AuthToken.TYPE_RESET_PASSWORD);
        assertThat(resetToken.rawToken()).isNotBlank();
        // Verify 1h expiry
        Duration resetDuration = Duration.between(before, resetToken.authToken().getExpiresAt());
        assertThat(resetDuration.toHours()).isEqualTo(1);
    }

    @Test
    void singleUseAndExpiryEnforcement() {
        UUID userId = testUser.getId();
        IssuedToken issued = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);

        // First use succeeds
        Optional<AuthToken> consumed = authTokenService.consumeToken(issued.rawToken(), AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(consumed).isPresent();
        assertThat(consumed.get().getUsedAt()).isNotNull();

        // Second use fails (single use)
        Optional<AuthToken> consumedAgain = authTokenService.consumeToken(issued.rawToken(), AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(consumedAgain).isEmpty();

        // Expired token cannot be consumed
        IssuedToken expiredIssued = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        AuthToken tokenEntity = authTokenRepository.findById(expiredIssued.authToken().getId()).orElseThrow();
        tokenEntity.setExpiresAt(Instant.now().minusSeconds(10));
        authTokenRepository.save(tokenEntity);

        Optional<AuthToken> consumedExpired = authTokenService.consumeToken(expiredIssued.rawToken(), AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(consumedExpired).isEmpty();
    }

    @Test
    void issuingNewTokenInvalidatesOlderUnusedTokensOfSameType() {
        UUID userId = testUser.getId();

        IssuedToken token1 = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        IssuedToken token2 = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);

        // token1 was invalidated by issuing token2
        Optional<AuthToken> consumed1 = authTokenService.consumeToken(token1.rawToken(), AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(consumed1).isEmpty();

        // token2 can be consumed
        Optional<AuthToken> consumed2 = authTokenService.consumeToken(token2.rawToken(), AuthToken.TYPE_VERIFY_EMAIL);
        assertThat(consumed2).isPresent();
        assertThat(consumed2.get().getId()).isEqualTo(token2.authToken().getId());
    }

    @Test
    void countTokensSinceForRateLimiting() {
        UUID userId = testUser.getId();
        authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);

        long count = authTokenService.countTokensSince(userId, AuthToken.TYPE_VERIFY_EMAIL, Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        assertThat(count).isEqualTo(2);

        long countReset = authTokenService.countTokensSince(userId, AuthToken.TYPE_RESET_PASSWORD, Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        assertThat(countReset).isEqualTo(0);
    }
}
