package com.followwise.identity;

import com.followwise.AbstractIntegrationTest;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

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

    @BeforeEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void refreshRotationHappyPathAndCookieFlags() throws Exception {
        String regJson = """
                {
                    "email": "refresh-user@example.com",
                    "password": "validPassword123"
                }
                """;

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andReturn();

        String cookieHeader = regResult.getResponse().getHeader("Set-Cookie");
        assertThat(cookieHeader).isNotNull();
        assertThat(cookieHeader).contains("HttpOnly");
        assertThat(cookieHeader).contains("SameSite=Strict");
        assertThat(cookieHeader).contains("Path=/api/auth");

        Cookie initialCookie = regResult.getResponse().getCookie("refreshToken");
        assertThat(initialCookie).isNotNull();

        // Perform refresh
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(initialCookie)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        Cookie rotatedCookie = refreshResult.getResponse().getCookie("refreshToken");
        assertThat(rotatedCookie).isNotNull();
        assertThat(rotatedCookie.getValue()).isNotEqualTo(initialCookie.getValue());
    }

    @Test
    void refreshWithoutClientHeaderReturns403ProblemJson() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", "dummyToken")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void refreshWithMismatchedOriginReturns403ProblemJson() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header("X-FollowWise-Client", "web")
                        .header("Origin", "http://malicious-site.com")
                        .cookie(new Cookie("refreshToken", "dummyToken")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void refreshReuseDetectionRevokesFamily() throws Exception {
        String regJson = """
                {
                    "email": "reuse-user@example.com",
                    "password": "validPassword123"
                }
                """;

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie token1 = regResult.getResponse().getCookie("refreshToken");

        // Rotate once: token1 -> token2
        MvcResult refresh1 = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(token1)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie token2 = refresh1.getResponse().getCookie("refreshToken");

        // Attempt to reuse token1
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(token1)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));

        // Verify all family rows are now revoked
        assertThat(refreshTokenRepository.findAll()).isNotEmpty();
        assertThat(refreshTokenRepository.findAll()).allMatch(t -> t.getRevokedAt() != null);

        // Attempting to use token2 now must also fail because family is revoked
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(token2)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void logoutRevokesFamilyAndClearsCookie() throws Exception {
        String regJson = """
                {
                    "email": "logout-user@example.com",
                    "password": "validPassword123"
                }
                """;

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie token = regResult.getResponse().getCookie("refreshToken");

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                        .cookie(token)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie clearedCookie = logoutResult.getResponse().getCookie("refreshToken");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isZero();

        // Refreshing with that token after logout must fail
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(token)
                        .header("X-FollowWise-Client", "web"))
                .andExpect(status().isUnauthorized());
    }
}
