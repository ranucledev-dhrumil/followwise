package com.followwise.identity;

import com.followwise.security.JwtProperties;
import com.followwise.security.TokenService;
import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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

    public AuthService(UserRepository userRepository,
                       WorkspaceRepository workspaceRepository,
                       MembershipRepository membershipRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordService passwordService,
                       TokenService tokenService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.jwtProperties = jwtProperties;
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

        return new AuthResult(accessToken, rawRefreshToken, userId, normalizedEmail);
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthResult login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        Instant now = Instant.now();

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
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

    private String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
