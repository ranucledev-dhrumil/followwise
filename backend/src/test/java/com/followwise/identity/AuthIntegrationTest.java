package com.followwise.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.followwise.AbstractIntegrationTest;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.WorkspaceRepository;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

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
    private AuthService authService;

    @BeforeEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerAndLoginHappyPath() throws Exception {
        String regJson = """
                {
                    "email": "Owner@Example.Com",
                    "password": "validPassword123",
                    "workspaceName": "Acme Corp"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(userRepository.findByEmail("owner@example.com")).isPresent();
        assertThat(workspaceRepository.findAll()).hasSize(1);
        assertThat(membershipRepository.findAll()).hasSize(1);
        assertThat(membershipRepository.findAll().get(0).getRole()).isEqualTo(Role.OWNER);

        String loginJson = """
                {
                    "email": "owner@example.com",
                    "password": "validPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void duplicateEmailRegistrationReturns409() throws Exception {
        String json = """
                {
                    "email": "user@example.com",
                    "password": "validPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveIdenticalGeneric401() throws Exception {
        String regJson = """
                {
                    "email": "user@example.com",
                    "password": "validPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated());

        String wrongPassJson = """
                {
                    "email": "user@example.com",
                    "password": "wrongPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPassJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));

        String unknownEmailJson = """
                {
                    "email": "unknown@example.com",
                    "password": "wrongPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownEmailJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void lockoutAfter5FailedAttempts() throws Exception {
        String regJson = """
                {
                    "email": "lockout@example.com",
                    "password": "correctPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated());

        String wrongPass = """
                {
                    "email": "lockout@example.com",
                    "password": "wrongPassword123"
                }
                """;

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongPass))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        }

        // 6th attempt with CORRECT password must still return identical 401
        String correctPass = """
                {
                    "email": "lockout@example.com",
                    "password": "correctPassword123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctPass))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void registrationRollbackOnFailure() {
        // Calling register with invalid password length (e.g. 9 chars) throws exception
        RegisterRequest invalidRequest = new RegisterRequest("rollback@example.com", "123456789", "My workspace");

        assertThatThrownBy(() -> authService.register(invalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(userRepository.findByEmail("rollback@example.com")).isEmpty();
        assertThat(workspaceRepository.findAll()).isEmpty();
        assertThat(membershipRepository.findAll()).isEmpty();
    }

    @Test
    void failedLoginDoesNotLogPasswordOrToken() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        String secretPass = "superSecretPasswordThatMustNeverBeLogged123";
        String loginJson = String.format("""
                {
                    "email": "notfound@example.com",
                    "password": "%s"
                }
                """, secretPass);

        try {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson))
                    .andExpect(status().isUnauthorized());

            for (ILoggingEvent event : listAppender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(secretPass);
            }
        } finally {
            rootLogger.detachAppender(listAppender);
        }
    }

    @Test
    void getMeReturnsUserProfileWithWorkspaces() throws Exception {
        String regJson = """
                {
                    "email": "me-user@example.com",
                    "password": "validPassword123",
                    "workspaceName": "My First Org"
                }
                """;

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andReturn();

        String accessToken = JsonPath.read(regResult.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me-user@example.com"))
                .andExpect(jsonPath("$.workspaces", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.workspaces[0].workspaceName").value("My First Org"))
                .andExpect(jsonPath("$.workspaces[0].role").value("OWNER"));
    }
}
