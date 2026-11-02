package com.followwise.identity;

import com.followwise.AbstractIntegrationTest;
import com.followwise.authtoken.AuthToken;
import com.followwise.authtoken.AuthTokenRepository;
import com.followwise.email.EmailSender;
import com.followwise.outbox.EmailOutbox;
import com.followwise.outbox.OutboxRepository;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.WorkspaceRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void registerEnqueuesVerificationEmailWithoutSendingInlineAndVerificationSucceeds() throws Exception {
        String email = "verify-" + UUID.randomUUID() + "@example.com";
        String body = """
                {
                    "email": "%s",
                    "password": "Password123!",
                    "workspaceName": "Acme Corp"
                }
                """.formatted(email);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");

        // Verify handler never sends inline
        verifyNoInteractions(emailSender);

        // Verify outbox row exists
        List<EmailOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        EmailOutbox outbox = outboxRows.get(0);
        assertThat(outbox.getKind()).isEqualTo(EmailOutbox.KIND_VERIFY_EMAIL);
        assertThat(outbox.getToAddress()).isEqualTo(email);
        assertThat(outbox.getStatus()).isEqualTo(EmailOutbox.STATUS_QUEUED);
        assertThat(outbox.getBodyText()).contains("/verify-email?token=");

        // Extract raw token from email body
        Matcher matcher = Pattern.compile("token=([a-zA-Z0-9_-]+)").matcher(outbox.getBodyText());
        assertThat(matcher.find()).isTrue();
        String rawToken = matcher.group(1);

        // Check user profile initially: emailVerified = false
        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));

        // Call POST /api/auth/verify-email
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());

        // Check user in DB: email_verified_at is set
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getEmailVerifiedAt()).isNotNull();

        // Check user profile now: emailVerified = true
        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        // Reuse token -> 400 Bad Request
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));

        // Invalid token -> 400 Bad Request
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"non-existent-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void resendVerificationEnforcesLimits() throws Exception {
        String email = "resend-" + UUID.randomUUID() + "@example.com";
        String body = """
                {
                    "email": "%s",
                    "password": "Password123!",
                    "workspaceName": "Acme Corp"
                }
                """.formatted(email);

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String accessToken = JsonPath.read(registerResult.getResponse().getContentAsString(), "$.accessToken");

        // Immediately calling resend within 60s of registration token -> 429 Too Many Requests
        mockMvc.perform(post("/api/auth/resend-verification")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429));

        // Update existing auth_tokens created_at via JdbcTemplate to 2 minutes ago to simulate passage of 60 seconds
        jdbcTemplate.update("UPDATE auth_tokens SET created_at = ?", Timestamp.from(Instant.now().minusSeconds(120)));

        // Resend now succeeds (204 No Content)
        mockMvc.perform(post("/api/auth/resend-verification")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // New outbox row was enqueued
        List<EmailOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(2);

        // Immediate subsequent call -> 429
        mockMvc.perform(post("/api/auth/resend-verification")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        // Simulate 5 tokens created in the last 24h
        User user = userRepository.findByEmail(email).orElseThrow();
        for (int i = 0; i < 4; i++) {
            jdbcTemplate.update(
                    "INSERT INTO auth_tokens (id, user_id, type, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    user.getId(),
                    AuthToken.TYPE_VERIFY_EMAIL,
                    "dummy-hash-" + i + "-" + UUID.randomUUID(),
                    Timestamp.from(Instant.now().plusSeconds(3600)),
                    Timestamp.from(Instant.now().minusSeconds(300 + i * 10))
            );
        }

        // Daily cap of 5 reached -> 429
        mockMvc.perform(post("/api/auth/resend-verification")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isTooManyRequests());

        // Unauthenticated call -> 401
        mockMvc.perform(post("/api/auth/resend-verification"))
                .andExpect(status().isUnauthorized());
    }
}
