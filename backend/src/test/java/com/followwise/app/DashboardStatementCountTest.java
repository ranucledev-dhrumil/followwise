package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyService;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DashboardStatementCountTest extends AbstractIntegrationTest {

    private static final AtomicInteger STATEMENT_COUNT = new AtomicInteger(0);

    @TestConfiguration
    static class CountingDataSourceConfig {
        @Bean
        public static BeanPostProcessor dataSourceProxyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass())) {
                        return Proxy.newProxyInstance(
                                ds.getClass().getClassLoader(),
                                new Class<?>[]{DataSource.class},
                                (proxy, method, args) -> {
                                    Object result = method.invoke(ds, args);
                                    if ("getConnection".equals(method.getName()) && result instanceof Connection conn) {
                                        return Proxy.newProxyInstance(
                                                Connection.class.getClassLoader(),
                                                new Class<?>[]{Connection.class},
                                                (cProxy, cMethod, cArgs) -> {
                                                    String mName = cMethod.getName();
                                                    if (mName.startsWith("prepare") || mName.startsWith("create")) {
                                                        STATEMENT_COUNT.incrementAndGet();
                                                    }
                                                    return cMethod.invoke(conn, cArgs);
                                                }
                                        );
                                    }
                                    return result;
                                }
                        );
                    }
                    return bean;
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

    @Autowired
    private PartyService partyService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspaceId;
    private UUID ownerId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        TestDbHelper.cleanDatabase(dataSource);

        Instant now = Instant.now();
        workspaceId = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspaceId, "Workspace 1", now));

        ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspaceId, Role.OWNER, now));
        ownerToken = tokenService.issueAccessToken(ownerId, now);
    }

    private void createParties(UUID appId, int count, int offset) {
        for (int i = 0; i < count; i++) {
            int num = offset + i;
            partyService.createParty(
                    workspaceId,
                    appId,
                    ownerId,
                    "tenant",
                    jsonMapper.createObjectNode()
                            .put("name", "Tenant " + num)
                            .put("email", "tenant" + num + "@example.com")
                            .put("phone", "+1 555-010" + (num % 100))
                            .put("monthly_rent", 100000 + (num * 100))
                            .put("due_day", 1 + (num % 28))
                            .put("lease_start", "2024-01-01")
                            .put("lease_end", "2024-12-31")
            );
        }
    }

    @Test
    void testStatementCountIdenticalFor10And100Parties() throws Exception {
        AppResponse app = appService.createApp(
                workspaceId,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", "America/New_York")
        );

        // 1. Create 10 parties
        createParties(app.id(), 10, 0);

        // Run dashboard and count statements
        STATEMENT_COUNT.set(0);
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/apps/" + app.id() + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        int statementsFor10 = STATEMENT_COUNT.get();

        // 2. Add 90 more parties (total 100)
        createParties(app.id(), 90, 10);

        // Run dashboard and count statements
        STATEMENT_COUNT.set(0);
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/apps/" + app.id() + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        int statementsFor100 = STATEMENT_COUNT.get();

        System.out.println("STATEMENT_COUNT_REPORT: 10 parties = " + statementsFor10 + " statements, 100 parties = " + statementsFor100 + " statements");
        assertEquals(statementsFor10, statementsFor100, "Dashboard statement count must be identical for 10 and 100 parties (no N+1)");
    }
}
