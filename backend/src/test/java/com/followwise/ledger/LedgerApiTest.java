package com.followwise.ledger;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.app.BodySizeFilter;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyService;
import com.followwise.party.RecordResponse;
import com.followwise.party.RecordService;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import(LedgerApiTest.TestClockConfig.class)
class LedgerApiTest extends AbstractIntegrationTest {

    static class MutableClock extends Clock {
        private Instant instant;
        private ZoneId zone;

        public MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(this.instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        static final MutableClock MUTABLE_CLOCK = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

        @Bean("testClock")
        @Primary
        public Clock testClock() {
            return MUTABLE_CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private RecordService recordService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BodySizeFilter bodySizeFilter;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspace1Id;
    private UUID workspace2Id;
    private UUID app1Id;
    private UUID app2Id;
    private UUID tenant1Id;
    private UUID unit1Id;

    private UUID ownerId;
    private UUID staffId;
    private UUID portalId;
    private UUID nonMemberId;

    private String ownerToken;
    private String staffToken;
    private String portalToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() {
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T12:00:00Z"));
        cleanDb();

        Instant now = Instant.now();
        ownerId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        portalId = UUID.randomUUID();
        nonMemberId = UUID.randomUUID();

        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        userRepository.save(new User(staffId, "staff@example.com", "hash", now));
        userRepository.save(new User(portalId, "portal@example.com", "hash", now));
        userRepository.save(new User(nonMemberId, "nonmember@example.com", "hash", now));

        Workspace ws1 = new Workspace(UUID.randomUUID(), "Workspace 1", now);
        Workspace ws2 = new Workspace(UUID.randomUUID(), "Workspace 2", now);
        workspaceRepository.save(ws1);
        workspaceRepository.save(ws2);
        workspace1Id = ws1.getId();
        workspace2Id = ws2.getId();

        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace1Id, Role.OWNER, now));
        membershipRepository.save(new Membership(UUID.randomUUID(), staffId, workspace1Id, Role.STAFF, now));
        membershipRepository.save(new Membership(UUID.randomUUID(), portalId, workspace1Id, Role.PORTAL_USER, now));
        membershipRepository.save(new Membership(UUID.randomUUID(), nonMemberId, workspace2Id, Role.OWNER, now));

        ownerToken = tokenService.issueAccessToken(ownerId, now);
        staffToken = tokenService.issueAccessToken(staffId, now);
        portalToken = tokenService.issueAccessToken(portalId, now);
        nonMemberToken = tokenService.issueAccessToken(nonMemberId, now);

        AppResponse app1 = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "RPM App", "UTC"));
        app1Id = app1.id();

        AppResponse app2 = appService.createApp(workspace2Id, nonMemberId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "RPM App 2", "UTC"));
        app2Id = app2.id();

        // Create a unit record
        try {
            RecordResponse unitResp = recordService.createRecord(workspace1Id, app1Id, ownerId, "unit",
                    jsonMapper.readTree("""
                            {"address": "Unit 101", "status": "occupied", "default_rent": 100000}
                            """));
            unit1Id = unitResp.id();

            // Create a tenant party referencing the unit
            var tenantResp = partyService.createParty(workspace1Id, app1Id, ownerId, "tenant",
                    jsonMapper.readTree(String.format("""
                            {
                              "name": "John Doe",
                              "email": "john@example.com",
                              "monthly_rent": 100000,
                              "due_day": 1,
                              "lease_start": "2026-01-01",
                              "lease_end": "2026-12-31",
                              "unit": "%s"
                            }
                            """, unit1Id)));
            tenant1Id = tenantResp.id();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        TestDbHelper.cleanDatabase(dataSource);
    }

    private String createChargeJson(String label, long amountCents, String dueDate, String scheduleId) {
        if (scheduleId == null) {
            return String.format("""
                    {
                      "label": "%s",
                      "amountCents": %d,
                      "dueDate": "%s"
                    }
                    """, label, amountCents, dueDate);
        } else {
            return String.format("""
                    {
                      "label": "%s",
                      "amountCents": %d,
                      "dueDate": "%s",
                      "scheduleId": "%s"
                    }
                    """, label, amountCents, dueDate, scheduleId);
        }
    }

    private String createPaymentJson(long amountCents, String paidOn, String note) {
        if (note == null) {
            return String.format("""
                    {
                      "amountCents": %d,
                      "paidOn": "%s"
                    }
                    """, amountCents, paidOn);
        } else {
            return String.format("""
                    {
                      "amountCents": %d,
                      "paidOn": "%s",
                      "note": "%s"
                    }
                    """, amountCents, paidOn, note);
        }
    }

    @Test
    void charge_plus_partial_payments_and_exact_payoff_and_overpayment_rejected() throws Exception {
        // 1. Create charge of $100 (10000 cents) due today
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("June Rent", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("CHARGE"))
                .andExpect(jsonPath("$.amountCents").value(10000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.label").value("June Rent"))
                .andReturn().getResponse().getContentAsString();

        JsonNode chargeNode = jsonMapper.readTree(chargeResp);
        String chargeId = chargeNode.get("id").asText();

        // Check charge status via GET /charges/{chargeId}: outstanding 10000, status open (due today is open, not overdue)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(10000))
                .andExpect(jsonPath("$.status").value("open"));

        // 2. Partial payment of $40 (4000 cents) by STAFF
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(4000, "2026-06-01", "Check #101")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("PAYMENT"))
                .andExpect(jsonPath("$.amountCents").value(-4000));

        // Outstanding is now 6000, status open
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(6000))
                .andExpect(jsonPath("$.status").value("open"));

        // 3. Overpayment by 1 cent (6001 cents) is rejected with 409 payment_exceeds_outstanding
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(6001, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("payment_exceeds_outstanding"));

        // 4. Exact payoff of $60 (6000 cents) gives paid
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(6000, "2026-06-01", "Cash")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(0))
                .andExpect(jsonPath("$.status").value("paid"));
    }

    @Test
    void payment_reversal_restores_outstanding_and_charge_reversal_rules() throws Exception {
        // Create charge of 10000
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("July Rent", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        // Record payment of 4000
        String paymentResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(4000, "2026-06-01", "Bounced check")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(paymentResp).get("id").asText();

        // Attempting to reverse charge while payment exists fails with 409 charge_has_payments
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + chargeId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Mistaken charge\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("charge_has_payments"));

        // Reverse the payment (reason: "Bounced check")
        String reversalResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Bounced check from tenant\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.amountCents").value(4000)) // Exact negation of -4000
                .andExpect(jsonPath("$.reversesEntryId").value(paymentId))
                .andReturn().getResponse().getContentAsString();
        String paymentReversalId = jsonMapper.readTree(reversalResp).get("id").asText();

        // Outstanding is restored to 10000
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(10000));

        // Reversing the already-reversed payment fails with 409 already_reversed
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Trying again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("already_reversed"));

        // Reversing a REVERSAL entry is rejected with 409 cannot_reverse_reversal
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentReversalId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Cannot reverse reversal\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("cannot_reverse_reversal"));

        // Now that active payments are 0 (outstanding == original amount 10000), reversing the charge succeeds
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + chargeId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Lease cancelled\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.amountCents").value(-10000));

        // Charge status is now "reversed"
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(0))
                .andExpect(jsonPath("$.status").value("reversed"));

        // Payment on a reversed charge is rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("charge_reversed"));
    }

    @Test
    void amount_edge_cases() throws Exception {
        // Zero cents rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Zero", 0, "2026-06-01", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/amountCents"));

        // Negative cents rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Negative", -500, "2026-06-01", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/amountCents"));

        // Float 100.0 rejected as bad request
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Float", "amountCents": 100.0, "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest());

        // Scientific notation 1e3 rejected as bad request
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Exp", "amountCents": 1e3, "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest());

        // Long overflow rejected as bad request
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Overflow", "amountCents": 99999999999999999999999999999999, "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest());

        // String "100" rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "String", "amountCents": "100", "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest());

        // Boolean true rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Bool", "amountCents": true, "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest());

        // 100,000,000,000 cents allowed
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("MaxAllowed", 100_000_000_000L, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountCents").value(100_000_000_000L));

        // 100,000,000,001 cents rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("AboveMax", 100_000_000_001L, "2026-06-01", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/amountCents"));
    }

    @Test
    void date_and_string_validation_boundaries() throws Exception {
        // due_date: today is 2026-06-01. Range is 2021-06-01 to 2031-06-01.
        // Exactly 5 years before allowed
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Past5Yr", 5000, "2021-06-01", null)))
                .andExpect(status().isCreated());

        // 5 years minus 1 day rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("TooOld", 5000, "2021-05-31", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/dueDate"));

        // 5 years plus 1 day in future rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("TooFarFuture", 5000, "2031-06-02", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/dueDate"));

        // paid_on boundaries: create a charge first
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("PaymentTarget", 50000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        // Future paid_on (2026-06-02) rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-02", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/paidOn"));

        // 1999-12-31 paid_on rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "1999-12-31", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/paidOn"));

        // 2000-01-01 paid_on allowed
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2000-01-01", null)))
                .andExpect(status().isCreated());

        // Label with control character or angle brackets rejected without echoing secret
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("<script>SECRET_MARKER_123</script>", 5000, "2026-06-01", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/label"))
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));

        // Note with control character rejected without echoing secret
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", "Note\\u0001controlSECRET_MARKER_123")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/note"))
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));

        // Reversal reason with angle brackets rejected without echoing secret
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + chargeId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"<b>SECRET_MARKER_123</b>\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/reason"))
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));
    }

    @Test
    void clock_test_two_apps_opposite_timezones() throws Exception {
        // App 1: Pacific/Kiritimati (UTC+14)
        AppResponse kiritimatiApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Kiritimati App", "Pacific/Kiritimati"));
        RecordResponse unitK = recordService.createRecord(workspace1Id, kiritimatiApp.id(), ownerId, "unit",
                jsonMapper.readTree("{\"address\": \"K Unit\", \"status\": \"occupied\", \"default_rent\": 50000}"));
        var tenantK = partyService.createParty(workspace1Id, kiritimatiApp.id(), ownerId, "tenant",
                jsonMapper.readTree(String.format("""
                        {"name": "K Tenant", "email": "k@example.com", "monthly_rent": 50000, "due_day": 1,
                         "lease_start": "2026-01-01", "lease_end": "2026-12-31", "unit": "%s"}
                        """, unitK.id())));

        // App 2: Pacific/Honolulu (UTC-10)
        AppResponse honoluluApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Honolulu App", "Pacific/Honolulu"));
        RecordResponse unitH = recordService.createRecord(workspace1Id, honoluluApp.id(), ownerId, "unit",
                jsonMapper.readTree("{\"address\": \"H Unit\", \"status\": \"occupied\", \"default_rent\": 50000}"));
        var tenantH = partyService.createParty(workspace1Id, honoluluApp.id(), ownerId, "tenant",
                jsonMapper.readTree(String.format("""
                        {"name": "H Tenant", "email": "h@example.com", "monthly_rent": 50000, "due_day": 1,
                         "lease_start": "2026-01-01", "lease_end": "2026-12-31", "unit": "%s"}
                        """, unitH.id())));

        // Fixed instant: 2026-06-01T12:00:00Z
        // In UTC+14: 2026-06-02 02:00:00 -> Date is 2026-06-02
        // In UTC-10: 2026-06-01 02:00:00 -> Date is 2026-06-01

        // Create charge due on 2026-06-01 in Honolulu: due today -> status is "open", not "overdue"
        String respH = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + honoluluApp.id() + "/parties/" + tenantH.id() + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Rent June 1", 50000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeHId = jsonMapper.readTree(respH).get("id").asText();

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + honoluluApp.id() + "/charges/" + chargeHId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"));

        // Create charge due on 2026-06-01 in Kiritimati: due yesterday (since today is June 2) -> status is "overdue"
        String respK = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + kiritimatiApp.id() + "/parties/" + tenantK.id() + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Rent June 1", 50000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeKId = jsonMapper.readTree(respK).get("id").asText();

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + kiritimatiApp.id() + "/charges/" + chargeKId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("overdue"));
    }

    @Test
    void schedule_id_validation_rules() throws Exception {
        // Valid charge schedule: "monthly_rent" is defined in rental-property-manager for "tenant"
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Rent with schedule", 10000, "2026-06-01", "monthly_rent")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value("monthly_rent"));

        // Non-existent schedule rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Invalid schedule", 10000, "2026-06-01", "nonexistent_sched")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/scheduleId"));

        // Add archived schedule and wrong-party-type schedule to definition
        String currentDef = jdbcClient.sql("SELECT definition FROM app_definition_versions WHERE app_id = ? ORDER BY version DESC LIMIT 1")
                .param(app1Id).query(String.class).single();
        var rootNode = (tools.jackson.databind.node.ObjectNode) jsonMapper.readTree(currentDef);
        var schedules = (tools.jackson.databind.node.ArrayNode) rootNode.get("chargeSchedules");
        var archivedSched = jsonMapper.createObjectNode()
                .put("id", "archived_sched")
                .put("label", "Archived Sched")
                .put("kind", "manual")
                .put("partyType", "tenant")
                .put("amountSource", "fixed")
                .put("enabled", true)
                .put("archived", true);
        var wrongPartySched = jsonMapper.createObjectNode()
                .put("id", "wrong_party_sched")
                .put("label", "Wrong Party Sched")
                .put("kind", "manual")
                .put("partyType", "wrong_party")
                .put("amountSource", "fixed")
                .put("enabled", true)
                .put("archived", false);
        schedules.add(archivedSched);
        schedules.add(wrongPartySched);
        jdbcClient.sql("UPDATE app_definition_versions SET definition = ?::jsonb WHERE app_id = ?")
                .param(rootNode.toString())
                .param(app1Id)
                .update();

        // Archived schedule rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Archived Schedule", 10000, "2026-06-01", "archived_sched")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/scheduleId"));

        // Wrong party type schedule rejected
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Wrong Party Schedule", 10000, "2026-06-01", "wrong_party_sched")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/scheduleId"));
    }

    @Test
    void archived_party_and_archived_app_rules() throws Exception {
        // Create charge first
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Rent Before Archive", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        // Archive the party
        partyService.archiveParty(workspace1Id, app1Id, tenant1Id, ownerId);

        // Attempting to create a new charge on archived party fails with 409 party_archived
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("New Rent", 10000, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("party_archived"));

        // Payment on archived party's existing charge succeeds
        String paymentResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(4000, "2026-06-01", "Post-archive payment")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(paymentResp).get("id").asText();

        // Reversal on archived party's payment succeeds
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Reverse post-archive payment\"}"))
                .andExpect(status().isCreated());

        // Now archive the app
        appService.archiveApp(workspace1Id, app1Id, ownerId);

        // Writes on archived app return 409 app_archived
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Rent on Archived App", 10000, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"));

        // Reads on archived app still succeed
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
    }

    @Test
    void permission_matrix_all_endpoints() throws Exception {
        // Create charge by OWNER succeeds
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Perm Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        // Create charge by STAFF -> 403 (MANAGE_LEDGER is OWNER only)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Staff Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isForbidden());

        // Create charge by PORTAL_USER -> 403
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Portal Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isForbidden());

        // Create charge by Non-member -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("NonMember Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isNotFound());

        // Payment by STAFF -> 201 (RECORD_PAYMENT is OWNER and STAFF)
        String paymentResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(2000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(paymentResp).get("id").asText();

        // Payment by PORTAL_USER -> 403
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(2000, "2026-06-01", null)))
                .andExpect(status().isForbidden());

        // Payment by Non-member -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(2000, "2026-06-01", null)))
                .andExpect(status().isNotFound());

        // Reverse by STAFF -> 403 (MANAGE_LEDGER is OWNER only)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Staff cannot reverse\"}"))
                .andExpect(status().isForbidden());

        // Reverse by PORTAL_USER -> 403
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Portal cannot reverse\"}"))
                .andExpect(status().isForbidden());

        // Reverse by Non-member -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"NonMember cannot reverse\"}"))
                .andExpect(status().isNotFound());

        // Read endpoints: GET charges, GET charge, GET party ledger
        // STAFF can read (VIEW_LEDGER)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/ledger")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // PORTAL_USER -> 403 on reads
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/ledger")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Non-member -> 404 on reads
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/ledger")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void scoping_other_app_and_other_workspace_and_unknown_id_returns_404() throws Exception {
        // App 1 charge
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Scoped Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        // 1. Trying to access App 1 charge via App 2 (different workspace) -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // 2. Trying to access via wrong workspace -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // 3. Unknown charge ID or party ID returns 404
        UUID unknownChargeId = UUID.randomUUID();
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + unknownChargeId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        UUID unknownPartyId = UUID.randomUUID();
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + unknownPartyId + "/ledger")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // 4. Writes scoped: payment, reverse, charge create on cross-app/cross-workspace IDs return 404
        String paymentResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(paymentResp).get("id").asText();

        // Cross-app charge create with party from another app -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Cross App", 1000, "2026-06-01", null)))
                .andExpect(status().isNotFound());

        // Cross-app payment on charge from another app -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", null)))
                .andExpect(status().isNotFound());

        // Cross-app reversal on entry from another app -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/ledger/" + paymentId + "/reverse")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Cross app reverse\"}"))
                .andExpect(status().isNotFound());

        // Cross-app party ledger -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/parties/" + tenant1Id + "/ledger")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotency_key_replay_conflict_and_concurrency() throws Exception {
        UUID idemKey = UUID.randomUUID();

        // 1. Initial request with Idempotency-Key
        String resp1 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Idem Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String entryId1 = jsonMapper.readTree(resp1).get("id").asText();

        // 2. Replay with exact same key, body, and user returns identical 201 response with same id
        String resp2 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Idem Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String entryId2 = jsonMapper.readTree(resp2).get("id").asText();
        assertEquals(entryId1, entryId2);

        // 3. Same key with different body returns 409 idempotency_conflict
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Different Label", 10000, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("idempotency_conflict"));

        // 4. Malformed Idempotency-Key returns 400
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Malformed Key", 10000, "2026-06-01", null)))
                .andExpect(status().isBadRequest());

        // 5. Same key with different user returns 409 idempotency_conflict (e.g. staff posting payment with key used by owner)
        String chargeForPayResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Target Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeForPayId = jsonMapper.readTree(chargeForPayResp).get("id").asText();

        UUID paymentIdemKey = UUID.randomUUID();
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeForPayId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .header("Idempotency-Key", paymentIdemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", null)))
                .andExpect(status().isCreated());

        // Owner reusing the same key returns 409 idempotency_conflict
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeForPayId + "/payments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", paymentIdemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(1000, "2026-06-01", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("idempotency_conflict"));

        // 6. Concurrent same-key requests create exactly one entry and two identical 201 responses
        UUID concurrentKey = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<String> idemTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                            .header("Authorization", "Bearer " + ownerToken)
                            .header("Idempotency-Key", concurrentKey.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createChargeJson("Concurrent Idem", 10000, "2026-06-01", null)))
                    .andReturn().getResponse().getContentAsString();
        };

        Future<String> f1 = executor.submit(idemTask);
        Future<String> f2 = executor.submit(idemTask);
        readyLatch.await();
        startLatch.countDown();

        String cResp1 = f1.get();
        String cResp2 = f2.get();
        executor.shutdown();

        String cId1 = jsonMapper.readTree(cResp1).get("id").asText();
        String cId2 = jsonMapper.readTree(cResp2).get("id").asText();
        assertEquals(cId1, cId2, "Both concurrent requests must return the same charge id");

        long count = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE idempotency_key = ?")
                .param(concurrentKey).query(Long.class).single();
        assertEquals(1, count, "Exactly one entry must be created");
    }

    @Test
    void concurrent_payments_of_60_percent_each_produce_exactly_one_success() throws Exception {
        // Create charge of 10000
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Race Charge", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> paymentTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                            .header("Authorization", "Bearer " + staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createPaymentJson(6000, "2026-06-01", "Race 60%")))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(paymentTask);
        Future<Integer> f2 = executor.submit(paymentTask);

        readyLatch.await();
        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        List<Integer> statuses = List.of(s1, s2);
        assertTrue(statuses.contains(201), "One payment must succeed with 201");
        assertTrue(statuses.contains(409), "Second payment must fail with 409 conflict");

        // Verify outstanding is 4000
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(4000));
    }

    @Test
    void concurrent_reversals_of_same_entry_produce_exactly_one_success() throws Exception {
        // Create charge and payment
        String chargeResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Reversal Race", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String chargeId = jsonMapper.readTree(chargeResp).get("id").asText();

        String paymentResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + chargeId + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(5000, "2026-06-01", "To reverse")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(paymentResp).get("id").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> reversalTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + paymentId + "/reverse")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"Concurrent reversal attempt\"}"))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(reversalTask);
        Future<Integer> f2 = executor.submit(reversalTask);

        readyLatch.await();
        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        List<Integer> statuses = List.of(s1, s2);
        assertTrue(statuses.contains(201), "One reversal must succeed with 201");
        assertTrue(statuses.contains(409), "Second reversal must fail with 409 conflict");
    }

    @Test
    void concurrent_creates_at_entry_limit_minus_one_produce_exactly_one_success() throws Exception {
        // Create an app dedicated to this limit test
        AppResponse limitApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Limit App", "UTC"));
        UUID limitAppId = limitApp.id();

        RecordResponse limitUnit = recordService.createRecord(workspace1Id, limitAppId, ownerId, "unit",
                jsonMapper.readTree("{\"address\": \"Limit Unit\", \"status\": \"occupied\", \"default_rent\": 50000}"));
        var limitTenant = partyService.createParty(workspace1Id, limitAppId, ownerId, "tenant",
                jsonMapper.readTree(String.format("""
                        {"name": "Limit Tenant", "email": "limit@example.com", "monthly_rent": 50000, "due_day": 1,
                         "lease_start": "2026-01-01", "lease_end": "2026-12-31", "unit": "%s"}
                        """, limitUnit.id())));
        UUID limitTenantId = limitTenant.id();

        // Insert 19999 entries directly via SQL generate_series so count is exactly limit - 1 (19999)
        jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, kind, label, due_date, created_by
                )
                SELECT
                    eid, :wid, :aid, :pid, eid, 'CHARGE',
                    1000, 'USD', 'manual', 'Batch charge', CURRENT_DATE, :uid
                FROM (SELECT gen_random_uuid() AS eid FROM generate_series(1, 19999)) sub
                """)
                .param("wid", workspace1Id)
                .param("aid", limitAppId)
                .param("pid", limitTenantId)
                .param("uid", ownerId)
                .update();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> createTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + limitAppId + "/parties/" + limitTenantId + "/charges")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createChargeJson("Race Charge Limit", 1000, "2026-06-01", null)))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(createTask);
        Future<Integer> f2 = executor.submit(createTask);

        readyLatch.await();
        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        List<Integer> statuses = List.of(s1, s2);
        assertTrue(statuses.contains(201), "One create must succeed with 201 at limit - 1");
        assertTrue(statuses.contains(409), "Second create must fail with 409 limit_exceeded");
    }

    @Test
    void immutability_triggers_and_database_integrity_checks() {
        // Insert a charge entry first so UPDATE and DELETE have a row to affect
        UUID testEntryId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, amount_cents, currency, kind, label, due_date, created_by)
                VALUES (:id, :wid, :aid, :pid, :cid, 'CHARGE', 1000, 'USD', 'manual', 'Test charge', CURRENT_DATE, :uid)
                """)
                .param("id", testEntryId)
                .param("wid", workspace1Id)
                .param("aid", app1Id)
                .param("pid", tenant1Id)
                .param("cid", testEntryId)
                .param("uid", ownerId)
                .update();

        // 1. Direct UPDATE on ledger_entries fails with trigger exception
        Exception updateEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("UPDATE ledger_entries SET amount_cents = 999 WHERE id = :id").param("id", testEntryId).update()
        );
        assertTrue(updateEx.getMessage().contains("ledger_entries is immutable"),
                "UPDATE must be blocked by immutability trigger: " + updateEx.getMessage());

        // 2. Direct DELETE on ledger_entries fails with trigger exception
        Exception deleteEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("DELETE FROM ledger_entries WHERE id = :id").param("id", testEntryId).update()
        );
        assertTrue(deleteEx.getMessage().contains("ledger_entries is immutable"),
                "DELETE must be blocked by immutability trigger: " + deleteEx.getMessage());

        // 3. Direct TRUNCATE on ledger_entries fails with trigger exception
        Exception truncateEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("TRUNCATE ledger_entries").update()
        );
        assertTrue(truncateEx.getMessage().contains("ledger_entries is immutable"),
                "TRUNCATE must be blocked by immutability trigger: " + truncateEx.getMessage());

        // 4. Direct SQL inserting a PAYMENT with positive amount fails DB CHECK
        Exception posPaymentEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("""
                        INSERT INTO ledger_entries (
                            id, workspace_id, app_id, party_id, charge_id, entry_type,
                            amount_cents, currency, paid_on, created_by
                        ) VALUES (
                            gen_random_uuid(), :wid, :aid, :pid, gen_random_uuid(), 'PAYMENT',
                            5000, 'USD', CURRENT_DATE, :uid
                        )
                        """)
                        .param("wid", workspace1Id)
                        .param("aid", app1Id)
                        .param("pid", tenant1Id)
                        .param("uid", ownerId)
                        .update()
        );
        assertNotNull(posPaymentEx);

        // 5. Direct SQL inserting an entry pointing at a charge in another app fails FK
        Exception crossAppChargeEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("""
                        INSERT INTO ledger_entries (
                            id, workspace_id, app_id, party_id, charge_id, entry_type,
                            amount_cents, currency, paid_on, created_by
                        ) VALUES (
                            gen_random_uuid(), :wid, :aid, :pid, :otherChargeId, 'PAYMENT',
                            -5000, 'USD', CURRENT_DATE, :uid
                        )
                        """)
                        .param("wid", workspace1Id)
                        .param("aid", app1Id)
                        .param("pid", tenant1Id)
                        .param("otherChargeId", UUID.randomUUID())
                        .param("uid", ownerId)
                        .update()
        );
        assertNotNull(crossAppChargeEx);

        // 6. Direct SQL inserting a REVERSAL pointing at another party's entry fails composite FK
        var tenant2Resp = partyService.createParty(workspace1Id, app1Id, ownerId, "tenant",
                jsonMapper.readTree(String.format("""
                        {
                          "name": "Jane Smith",
                          "email": "jane@example.com",
                          "monthly_rent": 120000,
                          "due_day": 1,
                          "lease_start": "2026-01-01",
                          "lease_end": "2026-12-31",
                          "unit": "%s"
                        }
                        """, unit1Id)));
        UUID tenant2Id = tenant2Resp.id();

        Exception crossPartyReversalEx = assertThrows(Exception.class, () ->
                jdbcClient.sql("""
                        INSERT INTO ledger_entries (
                            id, workspace_id, app_id, party_id, charge_id, entry_type,
                            amount_cents, currency, reverses_entry_id, created_by
                        ) VALUES (
                            gen_random_uuid(), :wid, :aid, :pid2, :cid, 'REVERSAL',
                            -1000, 'USD', :cid, :uid
                        )
                        """)
                        .param("wid", workspace1Id)
                        .param("aid", app1Id)
                        .param("pid2", tenant2Id)
                        .param("cid", testEntryId)
                        .param("uid", ownerId)
                        .update()
        );
        assertNotNull(crossPartyReversalEx);
    }

    @Test
    void party_ledger_summary_and_balances_with_mixed_entries() throws Exception {
        // Charge 1: 10000
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Charge 1", 10000, "2026-06-01", null)))
                .andExpect(status().isCreated());

        // Charge 2: 5000
        String charge2Resp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Charge 2", 5000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String charge2Id = jsonMapper.readTree(charge2Resp).get("id").asText();

        // Payment on Charge 2: 3000
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + charge2Id + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(3000, "2026-06-01", null)))
                .andExpect(status().isCreated());

        // GET party ledger: totalCharged = 15000, totalPaid = 3000, balance = 12000
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/ledger")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChargedCents").value(15000))
                .andExpect(jsonPath("$.summary.totalPaidCents").value(3000))
                .andExpect(jsonPath("$.summary.balanceCents").value(12000))
                .andExpect(jsonPath("$.items", hasSize(3)));
    }

    @Test
    void list_charges_filters_and_pagination() throws Exception {
        // Charge 1: overdue (due 2026-05-15, today is 2026-06-01)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Overdue Charge", 8000, "2026-05-15", null)))
                .andExpect(status().isCreated());

        // Charge 2: open (due 2026-06-01, today)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Due Today Charge", 12000, "2026-06-01", null)))
                .andExpect(status().isCreated());

        // Charge 3: paid (due 2026-06-01, fully paid)
        String c3Resp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("Paid Charge", 5000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String c3Id = jsonMapper.readTree(c3Resp).get("id").asText();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges/" + c3Id + "/payments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaymentJson(5000, "2026-06-01", null)))
                .andExpect(status().isCreated());

        // Filter: status=overdue -> exactly 1
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=overdue")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].label").value("Overdue Charge"));

        // Filter: status=open -> exactly 1 (Due Today is open, not overdue)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=open")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].label").value("Due Today Charge"));

        // Filter: status=paid -> exactly 1
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=paid")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].label").value("Paid Charge"));

        // Charge 4: reversed charge
        String c4Resp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createChargeJson("To Reverse Charge", 4000, "2026-06-01", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String c4Id = jsonMapper.readTree(c4Resp).get("id").asText();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/ledger/" + c4Id + "/reverse")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Reversing c4\"}"))
                .andExpect(status().isCreated());

        // Filter: status=all -> 4 (including reversed charge)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=all")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4));

        // Filter: status=reversed -> 1
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=reversed")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("reversed"))
                .andExpect(jsonPath("$.items[0].label").value("To Reverse Charge"));

        // Pagination: size=1, page=0
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=all&page=0&size=1")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(4));

        // Pagination: size=51 is capped at 50
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?status=all&size=51")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));

        // Negative page returns 400
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/charges?page=-1")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payload_envelope_and_size_protections() throws Exception {
        // Unknown property in request envelope returns 400
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Rent", "amountCents": 10000, "dueDate": "2026-06-01", "unknownProp": 123}
                                """))
                .andExpect(status().isBadRequest());

        // Duplicate JSON key returns 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "Rent", "amountCents": 10000, "amountCents": 20000, "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("duplicate JSON key"));

        // Body over 8 KB returns 413 via BodySizeFilter
        byte[] oversized = new byte[8193];
        Arrays.fill(oversized, (byte) ' ');
        byte[] validPrefix = "{\"label\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] validSuffix = "\",\"amountCents\":100,\"dueDate\":\"2026-06-01\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(validPrefix, 0, oversized, 0, validPrefix.length);
        System.arraycopy(validSuffix, 0, oversized, oversized.length - validSuffix.length, validSuffix.length);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        // Chunked stream over 8 KB without Content-Length
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenant1Id + "/charges");
        request.setContent(oversized);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[0]); // Clear buffered content
        // Set stream to oversized bytes
        byte[] streamBytes = new byte[9000];
        Arrays.fill(streamBytes, (byte) 'a');
        request.setContent(streamBytes);
        MockHttpServletResponse response = new MockHttpServletResponse();
        bodySizeFilter.doFilter(request, response, (req, res) -> {
            byte[] buf = new byte[1024];
            while (req.getInputStream().read(buf) != -1) {
                // read
            }
        });
        assertEquals(413, response.getStatus());
    }
}
