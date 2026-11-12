package com.followwise.identity;

import com.followwise.authtoken.AuthToken;
import com.followwise.authtoken.AuthTokenService;
import com.followwise.authtoken.IssuedToken;
import com.followwise.outbox.EmailOutbox;
import com.followwise.outbox.OutboxRepository;
import com.followwise.security.JwtProperties;
import com.followwise.security.TokenService;
import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordService passwordService;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;
    private final AuthTokenService authTokenService;
    private final OutboxRepository outboxRepository;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    public AuthService(UserRepository userRepository,
                       WorkspaceRepository workspaceRepository,
                       MembershipRepository membershipRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordService passwordService,
                       TokenService tokenService,
                       JwtProperties jwtProperties,
                       AuthTokenService authTokenService,
                       OutboxRepository outboxRepository) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.jwtProperties = jwtProperties;
        this.authTokenService = authTokenService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        passwordService.validatePassword(request.password());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException();
        }

        String workspaceName = request.workspaceName() != null && !request.workspaceName().isBlank()
                ? request.workspaceName().trim()
                : "My workspace";

        if (workspaceName.length() > 100) {
            throw new IllegalArgumentException("Workspace name must be between 1 and 100 characters");
        }

        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        String passwordHash = passwordService.encode(request.password());

        User user = new User(userId, normalizedEmail, passwordHash, now);
        userRepository.save(user);

        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace(workspaceId, workspaceName, now);
        workspaceRepository.save(workspace);

        UUID membershipId = UUID.randomUUID();
        Membership membership = new Membership(membershipId, userId, workspaceId, Role.OWNER, now);
        membershipRepository.save(membership);

        String accessToken = tokenService.issueAccessToken(userId, now);
        String rawRefreshToken = tokenService.generateRawRefreshToken();
        UUID familyId = UUID.randomUUID();
        Instant refreshExpiresAt = now.plus(Duration.ofDays(jwtProperties.getRefreshTtlDays()));

        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                userId,
                familyId,
                tokenService.hashToken(rawRefreshToken),
                now,
                refreshExpiresAt
        );
        refreshTokenRepository.save(refreshToken);

        // Enqueue verification email in the same transaction
        IssuedToken verifyToken = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        String baseUrl = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl : "http://localhost:3000";
        String verifyLink = baseUrl + "/verify-email?token=" + verifyToken.rawToken();
        EmailOutbox outbox = new EmailOutbox(
                UUID.randomUUID(),
                EmailOutbox.KIND_VERIFY_EMAIL,
                normalizedEmail,
                "Verify your FollowWise email",
                "Please verify your email: " + verifyLink,
                now
        );
        outboxRepository.save(outbox);

        return new AuthResult(accessToken, rawRefreshToken, userId, normalizedEmail);
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthResult login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        Instant now = Instant.now();

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty() || SystemUser.ID.equals(userOpt.get().getId())) {
            passwordService.runDummyMatch(request.password());
            throw new InvalidCredentialsException();
        }

        User user = userOpt.get();

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new InvalidCredentialsException();
        }

        if (!passwordService.matches(request.password(), user.getPasswordHash())) {
            userRepository.recordFailedAttempt(user.getId(), now.plus(Duration.ofMinutes(15)), now);
            throw new InvalidCredentialsException();
        }

        if (user.getFailedAttempts() > 0 || user.getLockedUntil() != null) {
            userRepository.resetFailedAttempts(user.getId(), now);
        }

        String accessToken = tokenService.issueAccessToken(user.getId(), now);
        String rawRefreshToken = tokenService.generateRawRefreshToken();
        UUID familyId = UUID.randomUUID();
        Instant refreshExpiresAt = now.plus(Duration.ofDays(jwtProperties.getRefreshTtlDays()));

        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                user.getId(),
                familyId,
                tokenService.hashToken(rawRefreshToken),
                now,
                refreshExpiresAt
        );
        refreshTokenRepository.save(refreshToken);

        return new AuthResult(accessToken, rawRefreshToken, user.getId(), user.getEmail());
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        String tokenHash = tokenService.hashToken(rawRefreshToken);

        int updated = refreshTokenRepository.markUsedIfValid(tokenHash, now);
        if (updated == 1) {
            RefreshToken oldToken = refreshTokenRepository.findByTokenHash(tokenHash)
                    .orElseThrow(InvalidCredentialsException::new);

            String newRawRefreshToken = tokenService.generateRawRefreshToken();
            Instant refreshExpiresAt = now.plus(Duration.ofDays(jwtProperties.getRefreshTtlDays()));

            RefreshToken newToken = new RefreshToken(
                    UUID.randomUUID(),
                    oldToken.getUserId(),
                    oldToken.getFamilyId(),
                    tokenService.hashToken(newRawRefreshToken),
                    now,
                    refreshExpiresAt
            );
            refreshTokenRepository.save(newToken);

            String newAccessToken = tokenService.issueAccessToken(oldToken.getUserId(), now);
            return new AuthResult(newAccessToken, newRawRefreshToken, oldToken.getUserId(), null);
        }

        Optional<RefreshToken> existing = refreshTokenRepository.findByTokenHash(tokenHash);
        existing.ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));

        throw new InvalidCredentialsException();
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            Instant now = Instant.now();
            String tokenHash = tokenService.hashToken(rawRefreshToken);
            Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);
            tokenOpt.ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));
        }
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        Optional<AuthToken> tokenOpt = authTokenService.consumeToken(rawToken, AuthToken.TYPE_VERIFY_EMAIL);
        if (tokenOpt.isEmpty()) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        AuthToken token = tokenOpt.get();
        if (SystemUser.ID.equals(token.getUserId())) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        Instant now = Instant.now();
        userRepository.markEmailVerified(token.getUserId(), now);
    }

    @Transactional
    public void resendVerification(UUID userId) {
        if (SystemUser.ID.equals(userId)) {
            throw new IllegalArgumentException("User not found");
        }
        Instant now = Instant.now();
        long countLast60s = authTokenService.countTokensSince(userId, AuthToken.TYPE_VERIFY_EMAIL, now.minus(60, ChronoUnit.SECONDS));
        if (countLast60s >= 1) {
            throw new RateLimitExceededException("Too many verification requests. Please wait before trying again.", 60);
        }
        long countLast24h = authTokenService.countTokensSince(userId, AuthToken.TYPE_VERIFY_EMAIL, now.minus(24, ChronoUnit.HOURS));
        if (countLast24h >= 5) {
            throw new RateLimitExceededException("Daily verification request limit reached.", 86400);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        IssuedToken verifyToken = authTokenService.issueToken(userId, AuthToken.TYPE_VERIFY_EMAIL);
        String baseUrl = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl : "http://localhost:3000";
        String verifyLink = baseUrl + "/verify-email?token=" + verifyToken.rawToken();
        EmailOutbox outbox = new EmailOutbox(
                UUID.randomUUID(),
                EmailOutbox.KIND_VERIFY_EMAIL,
                user.getEmail(),
                "Verify your FollowWise email",
                "Please verify your email: " + verifyLink,
                now
        );
        outboxRepository.save(outbox);
    }

    @Transactional
    public void forgotPassword(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty() || SystemUser.ID.equals(userOpt.get().getId())) {
            return;
        }
        User user = userOpt.get();
        Instant now = Instant.now();
        long countLastHour = authTokenService.countTokensSince(user.getId(), AuthToken.TYPE_RESET_PASSWORD, now.minus(1, ChronoUnit.HOURS));
        if (countLastHour >= 3) {
            return; // Enqueue nothing, return 204
        }

        IssuedToken resetToken = authTokenService.issueToken(user.getId(), AuthToken.TYPE_RESET_PASSWORD);
        String baseUrl = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl : "http://localhost:3000";
        String resetLink = baseUrl + "/reset-password?token=" + resetToken.rawToken();
        EmailOutbox outbox = new EmailOutbox(
                UUID.randomUUID(),
                EmailOutbox.KIND_RESET_PASSWORD,
                user.getEmail(),
                "Reset your FollowWise password",
                "Please reset your password: " + resetLink,
                now
        );
        outboxRepository.save(outbox);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        passwordService.validatePassword(newPassword);

        Optional<AuthToken> tokenOpt = authTokenService.consumeToken(rawToken, AuthToken.TYPE_RESET_PASSWORD);
        if (tokenOpt.isEmpty()) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        AuthToken token = tokenOpt.get();
        if (SystemUser.ID.equals(token.getUserId())) {
            throw new InvalidTokenException("Invalid or expired token");
        }

        Instant now = Instant.now();
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired token"));

        user.setPasswordHash(passwordService.encode(newPassword));
        user.setUpdatedAt(now);
        userRepository.save(user);

        // Reset lockout counters
        userRepository.resetFailedAttempts(user.getId(), now);

        // Revoke all refresh token families for user
        refreshTokenRepository.revokeAllForUser(user.getId(), now);

        // Enqueue PASSWORD_CHANGED notice
        EmailOutbox notice = new EmailOutbox(
                UUID.randomUUID(),
                EmailOutbox.KIND_PASSWORD_CHANGED,
                user.getEmail(),
                "Your FollowWise password was changed",
                "Your FollowWise password has been successfully changed.",
                now
        );
        outboxRepository.save(notice);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
