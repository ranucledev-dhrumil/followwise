package com.followwise.identity;

import com.followwise.AbstractIntegrationTest;
import com.followwise.authtoken.AuthToken;
import com.followwise.authtoken.AuthTokenRepository;
import com.followwise.email.EmailSender;
import com.followwise.outbox.EmailOutbox;
import com.followwise.outbox.OutboxRepository;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void cleanUp() {
        authTokenRepository.deleteAll();
        outboxRepository.deleteAll();
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void forgotPasswordResponseIsIdenticalForKnownAndUnknownEmailsAndEnforces3PerHourLimit() throws Exception {
        // Unknown email -> 204, enqueues nothing
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isNoContent());

        assertThat(outboxRepository.findAll()).isEmpty();

        // Register a known user
        String email = "forgot-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "%s",
                                    "password": "Password123!",
                                    "workspaceName": "Acme Corp"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());

        // Clear outbox (which has register's verify email)
        outboxRepository.deleteAll();

        // 1st request -> 204, enqueues 1 reset email
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        // Verify handler never sends inline
        verifyNoInteractions(emailSender);

        List<EmailOutbox> outboxList1 = outboxRepository.findAll();
        assertThat(outboxList1).hasSize(1);
        assertThat(outboxList1.get(0).getKind()).isEqualTo(EmailOutbox.KIND_RESET_PASSWORD);
        assertThat(outboxList1.get(0).getToAddress()).isEqualTo(email);

        // 2nd request -> 204, enqueues 2nd reset email
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(outboxRepository.findAll()).hasSize(2);

        // 3rd request -> 204, enqueues 3rd reset email
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(outboxRepository.findAll()).hasSize(3);

        // 4th request within an hour -> still 204, but enqueues NOTHING extra (still 3)
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(outboxRepository.findAll()).hasSize(3);
    }

    @Test
    void resetPasswordFlowRevokesRefreshFamiliesClearsLockoutAndEnqueuesNotice() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "%s",
                                    "password": "OldPassword123!",
                                    "workspaceName": "Acme Corp"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie refreshCookie = registerResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        // Lock the user by failing 5 logins
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "email": "%s",
                                        "password": "WrongPassword99!"
                                    }
                                    """.formatted(email)))
                    .andExpect(status().isUnauthorized());
        }

        User lockedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(lockedUser.getLockedUntil()).isNotNull();
        assertThat(lockedUser.getFailedAttempts()).isGreaterThanOrEqualTo(5);

        // Request password reset
        outboxRepository.deleteAll();
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        List<EmailOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        EmailOutbox resetEmail = outboxRows.get(0);
        assertThat(resetEmail.getKind()).isEqualTo(EmailOutbox.KIND_RESET_PASSWORD);

        Matcher matcher = Pattern.compile("token=([a-zA-Z0-9_-]+)").matcher(resetEmail.getBodyText());
        assertThat(matcher.find()).isTrue();
        String rawToken = matcher.group(1);

        // Try reset with invalid password (too short)
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "token": "%s",
                                    "newPassword": "short"
                                }
                                """.formatted(rawToken)))
                .andExpect(status().isBadRequest());

        // Perform valid reset
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "token": "%s",
                                    "newPassword": "NewPassword123!"
                                }
                                """.formatted(rawToken)))
                .andExpect(status().isOk());

        // Check lockout cleared
        User resetUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(resetUser.getLockedUntil()).isNull();
        assertThat(resetUser.getFailedAttempts()).isEqualTo(0);

        // Check PASSWORD_CHANGED notice enqueued
        List<EmailOutbox> allOutbox = outboxRepository.findAll();
        boolean hasNotice = allOutbox.stream()
                .anyMatch(e -> EmailOutbox.KIND_PASSWORD_CHANGED.equals(e.getKind()) && e.getToAddress().equals(email));
        assertThat(hasNotice).isTrue();

        // Old refresh token is revoked: calling /refresh returns 401
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isUnauthorized());

        // Login with old password fails
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "%s",
                                    "password": "OldPassword123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isUnauthorized());

        // Login with new password succeeds
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "%s",
                                    "password": "NewPassword123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk());

        // Reusing the reset token -> 400 Bad Request
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "token": "%s",
                                    "newPassword": "AnotherPassword123!"
                                }
                                """.formatted(rawToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
    }
}
