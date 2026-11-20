package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.dashboard.statement-timeout-ms=200")
class DashboardTimeoutTest extends AbstractIntegrationTest {

    private static final AtomicBoolean shouldSleep = new AtomicBoolean(true);

    @TestConfiguration
    static class SleepingStoreConfig {
        @Bean
        @Primary
        public DashboardStore sleepingDashboardStore(JdbcClient jdbcClient) {
            return new DashboardStore(jdbcClient) {
                @Override
                public void beforeFirstWidgetQuery() {
                    if (shouldSleep.compareAndSet(true, false)) {
                        jdbcClient.sql("SELECT pg_sleep(1)").query().listOfRows();
                    }
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AppService appService;

    private UUID workspaceId;
    private UUID ownerId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        shouldSleep.set(true);
        TestDbHelper.cleanDatabase(dataSource);

        Instant now = Instant.now();
        workspaceId = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspaceId, "Workspace 1", now));

        ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspaceId, Role.OWNER, now));
        ownerToken = tokenService.issueAccessToken(ownerId, now);
    }

    @Test
    void testDashboardTimeoutReturns503() throws Exception {
        AppResponse app = appService.createApp(
                workspaceId,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", "America/New_York")
        );

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/apps/" + app.id() + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("dashboard_timeout"))
                .andExpect(jsonPath("$.widgets").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        // Following normal request succeeds (the connection is not poisoned)
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/apps/" + app.id() + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets", hasSize(5)));
    }
}
