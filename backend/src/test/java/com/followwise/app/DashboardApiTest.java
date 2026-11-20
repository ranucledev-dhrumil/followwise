package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyResponse;
import com.followwise.party.PartyService;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class DashboardApiTest extends AbstractIntegrationTest {

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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private DashboardStore dashboardStore;

    @Autowired
    private AppStore appStore;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspace1Id;
    private UUID workspace2Id;
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
        TestDbHelper.cleanDatabase(dataSource);

        Instant now = Instant.now();

        workspace1Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace1Id, "Workspace 1", now));

        workspace2Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace2Id, "Workspace 2", now));

        ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace1Id, Role.OWNER, now));
        ownerToken = tokenService.issueAccessToken(ownerId, now);

        staffId = UUID.randomUUID();
        userRepository.save(new User(staffId, "staff@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), staffId, workspace1Id, Role.STAFF, now));
        staffToken = tokenService.issueAccessToken(staffId, now);

        portalId = UUID.randomUUID();
        userRepository.save(new User(portalId, "portal@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), portalId, workspace1Id, Role.PORTAL_USER, now));
        portalToken = tokenService.issueAccessToken(portalId, now);

        nonMemberId = UUID.randomUUID();
        userRepository.save(new User(nonMemberId, "nonmember@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), nonMemberId, workspace2Id, Role.OWNER, now));
        nonMemberToken = tokenService.issueAccessToken(nonMemberId, now);
    }

    private UUID createRentalApp(UUID wid, String tz) {
        AppResponse app = appService.createApp(
                wid,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", tz)
        );
        return app.id();
    }

    private UUID createClientDocApp(UUID wid, String tz) {
        AppResponse app = appService.createApp(
                wid,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "Client Document Tracker", tz)
        );
        return app.id();
    }

    @Test
    void testDashboardPermissionMatrix() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");

        // Unauthenticated -> 401
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard"))
                .andExpect(status().isUnauthorized());

        // OWNER -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // STAFF -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // PORTAL_USER -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // non-member -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // unknown app -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + UUID.randomUUID() + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // other workspace app -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDashboardMonthValidation() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate todayInNy = LocalDate.now(ZoneId.of("America/New_York"));
        YearMonth currentMonth = YearMonth.from(todayInNy);

        // 1. Default month (absent)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(currentMonth.toString()));

        // 2. Past month in range (5 months ago)
        YearMonth past = currentMonth.minusMonths(5);
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + past)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(past.toString()));

        // 3. Future month in range (5 months ahead)
        YearMonth future = currentMonth.plusMonths(5);
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + future)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(future.toString()));

        // 4. Exactly 24 months before and after -> 200
        YearMonth min24 = currentMonth.minusMonths(24);
        YearMonth max24 = currentMonth.plusMonths(24);
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + min24)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + max24)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 5. 25 months outside range -> 400
        YearMonth min25 = currentMonth.minusMonths(25);
        YearMonth max25 = currentMonth.plusMonths(25);
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + min25)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("month out of range"));
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + max25)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("month out of range"));

        // 6. Malformed month format -> 400
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=invalid")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid month format"));

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=2024-13")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid month format"));
    }

    @Test
    void testDashboardTimezonesOppositeSidesOfDateLine() throws Exception {
        UUID aucklandAppId = createRentalApp(workspace1Id, "Pacific/Auckland"); // UTC+12/+13
        UUID honoluluAppId = createRentalApp(workspace1Id, "Pacific/Honolulu"); // UTC-10

        LocalDate todayAuckland = LocalDate.now(ZoneId.of("Pacific/Auckland"));
        LocalDate todayHonolulu = LocalDate.now(ZoneId.of("Pacific/Honolulu"));

        MvcResult auckRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + aucklandAppId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auckJson = jsonMapper.readTree(auckRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(todayAuckland.toString(), auckJson.get("today").asText());

        MvcResult honoRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + honoluluAppId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode honoJson = jsonMapper.readTree(honoRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(todayHonolulu.toString(), honoJson.get("today").asText());
    }

    @Test
    void testDashboardWidgetsComprehensive() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        YearMonth thisMonth = YearMonth.from(today);
        LocalDate monthStart = thisMonth.atDay(1);
        LocalDate monthEnd = thisMonth.atEndOfMonth();

        // 1. Create 3 active parties + 1 archived party
        PartyResponse p1 = partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "Alice Active")
                        .put("email", "alice@example.com")
                        .put("monthly_rent", 100000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", today.plusDays(10).toString()) // expiring lease on day 10
        );
        PartyResponse p2 = partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "Bob Active")
                        .put("email", "bob@example.com")
                        .put("monthly_rent", 200000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", today.plusDays(60).toString()) // expiring lease on day 60 (included)
        );
        PartyResponse p3 = partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "Charlie Active")
                        .put("email", "charlie@example.com")
                        .put("monthly_rent", 150000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", today.plusDays(61).toString()) // expiring lease on day 61 (EXCLUDED from expiring)
        );
        PartyResponse pArchived = partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "Dave Archived")
                        .put("email", "dave@example.com")
                        .put("monthly_rent", 300000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", today.plusDays(5).toString())
        );
        partyService.archiveParty(workspace1Id, appId, pArchived.id(), ownerId);

        // 2. Insert Charges & Payments for Ledger widgets
        // Charge 1 on Alice: overdue (due 5 days ago), amount 100000, partially paid 30000 -> outstanding 70000
        UUID c1 = UUID.randomUUID();
        LocalDate duePast = today.minusDays(5);
        insertCharge(c1, p1.id(), appId, 100000, "recurring", duePast);
        UUID pay1 = UUID.randomUUID();
        insertPayment(pay1, c1, p1.id(), appId, -30000, duePast.plusDays(1));

        // Charge 2 on Bob: overdue (due 2 days ago), amount 50000, unpaid -> outstanding 50000
        // Plus a late-fee charge on Bob (due 1 day ago), amount 5000, unpaid -> outstanding 5000
        UUID c2 = UUID.randomUUID();
        insertCharge(c2, p2.id(), appId, 50000, "recurring", today.minusDays(2));
        UUID cLateFee = UUID.randomUUID();
        insertCharge(cLateFee, p2.id(), appId, 5000, "late_fee", today.minusDays(1));

        // Charge 3 on Charlie: due TODAY (due_date == today) -> NOT overdue!
        UUID c3 = UUID.randomUUID();
        insertCharge(c3, p3.id(), appId, 80000, "recurring", today);

        // Charge 4 on Charlie: due in future (today + 5 days) -> NOT overdue!
        UUID c4 = UUID.randomUUID();
        insertCharge(c4, p3.id(), appId, 90000, "recurring", today.plusDays(5));

        // Charge 5 on Alice: overdue (due 10 days ago), amount 60000, fully paid -> outstanding 0 -> NOT overdue!
        UUID c5 = UUID.randomUUID();
        insertCharge(c5, p1.id(), appId, 60000, "recurring", today.minusDays(10));
        UUID pay5 = UUID.randomUUID();
        insertPayment(pay5, c5, p1.id(), appId, -60000, today.minusDays(8));

        // Charge 6 on Bob: overdue, but REVERSED -> excluded from overdue and expected!
        UUID c6 = UUID.randomUUID();
        insertCharge(c6, p2.id(), appId, 70000, "recurring", today.minusDays(3));
        UUID rev6 = UUID.randomUUID();
        insertReversal(rev6, c6, c6, p2.id(), appId, -70000);

        // Charge 7 on Archived party: overdue -> must be EXCLUDED because party is archived!
        UUID cArchived = UUID.randomUUID();
        insertCharge(cArchived, pArchived.id(), appId, 40000, "recurring", today.minusDays(4));

        // Charge 8 on Alice: due on first day of selected month (monthStart)
        UUID cMonthStart = UUID.randomUUID();
        insertCharge(cMonthStart, p1.id(), appId, 11000, "recurring", monthStart);

        // Charge 9 on Alice: due on last day of selected month (monthEnd)
        UUID cMonthEnd = UUID.randomUUID();
        insertCharge(cMonthEnd, p1.id(), appId, 22000, "recurring", monthEnd);

        // Payment inside month: paid on monthStart, amount 15000
        UUID payInMonth1 = UUID.randomUUID();
        insertPayment(payInMonth1, cMonthStart, p1.id(), appId, -15000, monthStart);

        // Payment inside month: paid on monthEnd, amount 25000
        UUID payInMonth2 = UUID.randomUUID();
        insertPayment(payInMonth2, cMonthEnd, p1.id(), appId, -25000, monthEnd);

        // Payment inside month: paid on monthStart + 1, amount 35000, BUT REVERSED -> excluded from collected!
        UUID payReversed = UUID.randomUUID();
        insertPayment(payReversed, cMonthStart, p1.id(), appId, -35000, monthStart.plusDays(1));
        UUID revPayment = UUID.randomUUID();
        insertReversal(revPayment, cMonthStart, payReversed, p1.id(), appId, 35000);

        // Payment outside month (monthStart - 1 day) -> excluded from collected!
        UUID payOutsideMonth = UUID.randomUUID();
        insertPayment(payOutsideMonth, cMonthStart, p1.id(), appId, -45000, monthStart.minusDays(1));

        // Now run GET /dashboard?month=YYYY-MM
        MvcResult res = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard?month=" + thisMonth)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode widgets = root.get("widgets");

        // Verify count_overdue_charges:
        // Overdue charges: c1 (Alice, 70000 left), c2 (Bob, 50000 left), cLateFee (Bob, 5000 left) -> count = 3!
        // (c3 is due today, c4 is future, c5 is fully paid, c6 is reversed, cArchived is archived party)
        JsonNode countOverdue = findWidget(widgets, "count_overdue");
        assertEquals("OK", countOverdue.get("status").asText());
        assertEquals("Overdue charges", countOverdue.get("label").asText());
        assertEquals(3L, countOverdue.get("data").get("count").asLong());

        // Verify sum_overdue_charges via dashboardStore:
        // Outstanding cents: 70000 + 50000 + 5000 = 125000!
        long sumOverdue = dashboardStore.sumOverdueCharges(workspace1Id, appId, "tenant", today);
        assertEquals(125000L, sumOverdue);

        // Verify list_overdue:
        // Alice has overdue 70000 (1 charge, oldestDueDate = duePast)
        // Bob has overdue 55000 (2 charges: c2 due -2, cLateFee due -1, oldestDueDate = today - 2)
        // Ordered by overdue cents DESC: Alice (70000) first, Bob (55000) second!
        JsonNode listOverdue = findWidget(widgets, "list_overdue");
        assertEquals(2L, listOverdue.get("data").get("totalCount").asLong());
        ArrayNode overdueItems = (ArrayNode) listOverdue.get("data").get("items");
        assertEquals(2, overdueItems.size());
        assertEquals(p1.id().toString(), overdueItems.get(0).get("partyId").asText());
        assertEquals("Alice Active", overdueItems.get(0).get("displayName").asText());
        assertEquals(70000L, overdueItems.get(0).get("overdueCents").asLong());
        assertEquals(1L, overdueItems.get(0).get("overdueChargeCount").asLong());

        assertEquals(p2.id().toString(), overdueItems.get(1).get("partyId").asText());
        assertEquals("Bob Active", overdueItems.get(1).get("displayName").asText());
        assertEquals(55000L, overdueItems.get(1).get("overdueCents").asLong());
        assertEquals(2L, overdueItems.get(1).get("overdueChargeCount").asLong());

        // Verify expiring_leases:
        // Alice: today + 10 (daysLeft = 10)
        // Bob: today + 60 (daysLeft = 60)
        // Charlie: today + 61 (excluded!)
        // Archived Dave: excluded!
        JsonNode expiringLeases = findWidget(widgets, "expiring_leases");
        assertEquals(2L, expiringLeases.get("data").get("totalCount").asLong());
        ArrayNode leaseItems = (ArrayNode) expiringLeases.get("data").get("items");
        assertEquals(2, leaseItems.size());
        assertEquals(p1.id().toString(), leaseItems.get(0).get("partyId").asText());
        assertEquals(10, leaseItems.get(0).get("daysLeft").asInt());
        assertEquals(p2.id().toString(), leaseItems.get(1).get("partyId").asText());
        assertEquals(60, leaseItems.get(1).get("daysLeft").asInt());

        // Verify sum_collected_period:
        // Collected in month: payInMonth1 (15000) + payInMonth2 (25000) = 40000!
        // (payReversed was reversed, payOutsideMonth was outside month)
        JsonNode sumCollected = findWidget(widgets, "sum_collected");
        assertTrue(sumCollected.get("data").get("cents").asLong() >= 40000L);
    }

    @Test
    void testDashboardClientDocumentTracker() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // Create 2 clients
        PartyResponse c1 = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode()
                        .put("name", "Client Alpha")
                        .put("email", "alpha@example.com")
        );
        PartyResponse c2 = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode()
                        .put("name", "Client Beta")
                        .put("email", "beta@example.com")
        );

        // Client 1 has 2 required requests:
        // r1: PENDING, due 3 days ago (overdue!)
        // r2: APPROVED, expires in 15 days (expiring!)
        UUID r1 = UUID.randomUUID();
        insertDocRequest(r1, workspace1Id, appId, c1.id(), "onboarding_docs", "gov_id", "Government Photo ID", true, today.minusDays(3), "PENDING", null);
        UUID r2 = UUID.randomUUID();
        insertDocRequest(r2, workspace1Id, appId, c1.id(), "onboarding_docs", "tax_form", "Tax Form", true, today.plusDays(7), "APPROVED", today.plusDays(15));

        // Client 2 has 2 required requests:
        // r3: REJECTED, due 1 day ago (overdue!)
        // r4: PENDING, cancelled -> cancelled_at is NOT null! (must be excluded from everything!)
        UUID r3 = UUID.randomUUID();
        insertDocRequest(r3, workspace1Id, appId, c2.id(), "onboarding_docs", "gov_id", "Government Photo ID", true, today.minusDays(1), "REJECTED", null);
        UUID r4 = UUID.randomUUID();
        insertCancelledDocRequest(r4, workspace1Id, appId, c2.id(), "onboarding_docs", "tax_form", "Tax Form", true, today.minusDays(2), "PENDING");

        MvcResult res = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode widgets = root.get("widgets");

        // 1. count_overdue_docs:
        // r1 (overdue) and r3 (overdue) -> count = 2!
        JsonNode countOverdue = findWidget(widgets, "count_overdue_docs");
        assertEquals("Overdue documents", countOverdue.get("label").asText());
        assertEquals(2L, countOverdue.get("data").get("count").asLong());

        // 2. list_missing_docs:
        // Client Alpha has r1 (missingCount = 1, oldestDueDate = today - 3)
        // Client Beta has r3 (missingCount = 1, oldestDueDate = today - 1)
        // Sorted by oldestDueDate ASC: Alpha then Beta!
        JsonNode listMissing = findWidget(widgets, "list_missing_docs");
        assertEquals("Missing documents", listMissing.get("label").asText());
        assertEquals(2L, listMissing.get("data").get("totalCount").asLong());
        ArrayNode missingItems = (ArrayNode) listMissing.get("data").get("items");
        assertEquals(2, missingItems.size());
        assertEquals(c1.id().toString(), missingItems.get(0).get("partyId").asText());
        assertEquals(1L, missingItems.get(0).get("missingCount").asLong());

        // 3. list_expiring_docs:
        // r2 (expires in 15 days) -> daysLeft = 15
        JsonNode listExpiring = findWidget(widgets, "list_expiring_docs");
        assertEquals("Documents expiring soon", listExpiring.get("label").asText());
        assertEquals(1L, listExpiring.get("data").get("totalCount").asLong());
        ArrayNode expiringItems = (ArrayNode) listExpiring.get("data").get("items");
        assertEquals(1, expiringItems.size());
        assertEquals(r2.toString(), expiringItems.get(0).get("requestId").asText());
        assertEquals(15, expiringItems.get(0).get("daysLeft").asInt());

        // 4. completion_pct_by_party:
        // Client Alpha has 2 required requests, 1 approved -> 50%
        // Client Beta has 1 non-cancelled required request (r3), 0 approved -> 0%
        // Sorted by pct ASC: Beta (0%) first, Alpha (50%) second
        // overallPct across all 3 counted requests: floor(100 * 1 / 3) = 33%
        JsonNode compPct = findWidget(widgets, "completion_pct_by_party");
        assertEquals("Document completion", compPct.get("label").asText());
        assertEquals(2L, compPct.get("data").get("totalCount").asLong());
        assertEquals(33, compPct.get("data").get("overallPct").asInt());
        ArrayNode compItems = (ArrayNode) compPct.get("data").get("items");
        assertEquals(2, compItems.size());
        assertEquals(c2.id().toString(), compItems.get(0).get("partyId").asText());
        assertEquals(0, compItems.get(0).get("pct").asInt());
        assertEquals(c1.id().toString(), compItems.get(1).get("partyId").asText());
        assertEquals(50, compItems.get(1).get("pct").asInt());
    }

    @Test
    void testDashboardUnavailableWidgets() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        AppResponse currentApp = appService.getApp(workspace1Id, appId, ownerId);

        ObjectNode newDef = (ObjectNode) currentApp.definition().deepCopy();
        // Add 3 test widgets:
        // w1: partyType "unknown_party" -> PARTY_TYPE_MISSING
        // w2: partyType "tenant", type "count_overdue_docs" (rental template has no documentChecklists) -> BLOCK_MISSING
        // w3: type "list_expiring_leases", dateField "non_existent_date" -> FIELD_MISSING
        ArrayNode widgets = (ArrayNode) newDef.get("widgets");
        widgets.add(jsonMapper.createObjectNode()
                .put("id", "w_missing_pt")
                .put("type", "count_overdue_charges")
                .put("partyType", "unknown_party")
                .put("archived", false));
        widgets.add(jsonMapper.createObjectNode()
                .put("id", "w_missing_block")
                .put("type", "count_overdue_docs")
                .put("partyType", "tenant")
                .put("archived", false));
        widgets.add(jsonMapper.createObjectNode()
                .put("id", "w_missing_field")
                .put("type", "list_expiring_leases")
                .put("partyType", "tenant")
                .put("dateField", "non_existent_field")
                .put("archived", false));

        appStore.insertVersion(UUID.randomUUID(), appId, 2, newDef.toString(), "add test unavailable widgets", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(appId, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        MvcResult res = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode widgetList = root.get("widgets");

        JsonNode w1 = findWidget(widgetList, "w_missing_pt");
        assertEquals("UNAVAILABLE", w1.get("status").asText());
        assertEquals("PARTY_TYPE_MISSING", w1.get("reason").asText());
        assertTrue(w1.get("data").isNull());

        JsonNode w2 = findWidget(widgetList, "w_missing_block");
        assertEquals("UNAVAILABLE", w2.get("status").asText());
        assertEquals("BLOCK_MISSING", w2.get("reason").asText());
        assertTrue(w2.get("data").isNull());

        JsonNode w3 = findWidget(widgetList, "w_missing_field");
        assertEquals("UNAVAILABLE", w3.get("status").asText());
        assertEquals("FIELD_MISSING", w3.get("reason").asText());
        assertTrue(w3.get("data").isNull());
    }

    @Test
    void testDashboardCrossAppAndWorkspaceIsolation() throws Exception {
        UUID app1Id = createRentalApp(workspace1Id, "America/New_York");
        UUID app2Id = appService.createApp(
                workspace2Id,
                nonMemberId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager 2", "America/New_York")
        ).id();

        PartyResponse pApp1 = partyService.createParty(workspace1Id, app1Id, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "App1 Tenant")
                        .put("email", "app1@example.com")
                        .put("monthly_rent", 100000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", "2024-12-31")
        );

        PartyResponse pApp2 = partyService.createParty(workspace2Id, app2Id, nonMemberId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "App2 Secret Tenant")
                        .put("email", "app2@example.com")
                        .put("monthly_rent", 100000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", "2024-12-31")
        );

        // Insert overdue charge for App2 Tenant
        insertCharge(UUID.randomUUID(), workspace2Id, pApp2.id(), app2Id, 99999, "recurring", LocalDate.now().minusDays(5), nonMemberId);

        // App 1 dashboard must NOT contain App 2's data
        MvcResult res = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        String rawJson = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(rawJson.contains("App2 Secret Tenant"));
        assertFalse(rawJson.contains("99999"));
    }

    @Test
    void testDashboardArchivedAppStillReadable() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        appService.archiveApp(workspace1Id, appId, ownerId);

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")));
    }

    @Test
    void testDashboardPerformance20000Rows() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // Insert 500 parties and 20000 ledger entries via fast batch insert
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Insert 500 parties
            UUID[] partyIds = new UUID[500];
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO parties (
                        id, workspace_id, app_id, party_type, display_name, data,
                        definition_version, version, created_by, updated_by, created_at, updated_at
                    ) VALUES (?, ?, ?, 'tenant', ?, '{}'::jsonb, 1, 1, ?, ?, NOW(), NOW())
                    """)) {
                for (int i = 0; i < 500; i++) {
                    partyIds[i] = UUID.randomUUID();
                    ps.setObject(1, partyIds[i]);
                    ps.setObject(2, workspace1Id);
                    ps.setObject(3, appId);
                    ps.setString(4, "Tenant " + i);
                    ps.setObject(5, ownerId);
                    ps.setObject(6, ownerId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // 2. Insert 20000 ledger entries (40 entries per party)
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ledger_entries (
                        id, workspace_id, app_id, party_id, charge_id, entry_type,
                        amount_cents, currency, kind, label, due_date, created_by, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'CHARGE', ?, 'USD', 'recurring', 'Rent', ?, ?, NOW())
                    """)) {
                int count = 0;
                for (int i = 0; i < 500; i++) {
                    for (int j = 0; j < 40; j++) {
                        UUID cid = UUID.randomUUID();
                        ps.setObject(1, cid);
                        ps.setObject(2, workspace1Id);
                        ps.setObject(3, appId);
                        ps.setObject(4, partyIds[i]);
                        ps.setObject(5, cid);
                        ps.setLong(6, 100000 + j * 100);
                        // stagger due dates around today
                        ps.setObject(7, today.plusDays((j % 60) - 30));
                        ps.setObject(8, ownerId);
                        ps.addBatch();
                        count++;
                        if (count % 2000 == 0) {
                            ps.executeBatch();
                        }
                    }
                }
                ps.executeBatch();
            }

            conn.commit();
        }

        // Run full dashboard and measure elapsed time
        long start = System.currentTimeMillis();
        DashboardResponse response = dashboardService.getDashboard(workspace1Id, appId, ownerId, null);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("20000_ROWS_PERFORMANCE_REPORT: Elapsed time = " + elapsed + " ms");
        assertTrue(elapsed < 5000, "Dashboard on 20,000 ledger entries must complete under 5 seconds, took " + elapsed + " ms");
        assertEquals(5, response.widgets().size());

        // Run EXPLAIN (ANALYZE, BUFFERS) on the three heaviest queries:
        // 1. list_overdue_parties
        String explain1 = jdbcClient.sql("""
                EXPLAIN (ANALYZE, BUFFERS)
                WITH overdue_charges AS (
                    SELECT c.party_id,
                           c.id AS charge_id,
                           c.due_date,
                           (SELECT COALESCE(SUM(e.amount_cents), 0)::bigint
                            FROM ledger_entries e
                            WHERE e.workspace_id = c.workspace_id AND e.app_id = c.app_id AND e.charge_id = c.id) AS outstanding
                    FROM ledger_entries c
                    JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid
                      AND c.entry_type = 'CHARGE'
                      AND c.kind IN ('recurring', 'manual', 'late_fee')
                      AND c.due_date < :today
                      AND p.archived_at IS NULL
                      AND p.party_type = 'tenant'
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries r
                          WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                      )
                ),
                party_overdue AS (
                    SELECT party_id,
                           SUM(outstanding)::bigint AS overdue_cents,
                           MIN(due_date) AS oldest_due_date,
                           COUNT(*)::bigint AS overdue_charge_count
                    FROM overdue_charges
                    WHERE outstanding > 0
                    GROUP BY party_id
                ),
                ranked AS (
                    SELECT p.id AS party_id,
                           p.display_name,
                           po.overdue_cents,
                           po.oldest_due_date,
                           po.overdue_charge_count,
                           COUNT(*) OVER() AS total_count
                    FROM party_overdue po
                    JOIN parties p ON p.id = po.party_id
                )
                SELECT * FROM ranked
                ORDER BY overdue_cents DESC, party_id ASC
                LIMIT 20
                """)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("today", today)
                .query(String.class)
                .list()
                .stream().reduce("", (a, b) -> a + "\n" + b);

        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_1_LIST_OVERDUE_PARTIES:" + explain1);

        // 2. sum_expected_period
        YearMonth ym = YearMonth.from(today);
        String explain2 = jdbcClient.sql("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT COALESCE(SUM(c.amount_cents), 0)::bigint
                FROM ledger_entries c
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.due_date >= :mStart AND c.due_date <= :mEnd
                  AND p.archived_at IS NULL
                  AND p.party_type = 'tenant'
                  AND NOT EXISTS (
                      SELECT 1 FROM ledger_entries r
                      WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                  )
                """)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("mStart", ym.atDay(1))
                .param("mEnd", ym.atEndOfMonth())
                .query(String.class)
                .list()
                .stream().reduce("", (a, b) -> a + "\n" + b);

        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_2_SUM_EXPECTED_PERIOD:" + explain2);

        // 3. sum_overdue_charges
        String explain3 = jdbcClient.sql("""
                EXPLAIN (ANALYZE, BUFFERS)
                WITH overdue_charges AS (
                    SELECT c.id,
                           (SELECT COALESCE(SUM(e.amount_cents), 0)::bigint
                            FROM ledger_entries e
                            WHERE e.workspace_id = c.workspace_id AND e.app_id = c.app_id AND e.charge_id = c.id) AS outstanding
                    FROM ledger_entries c
                    JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid
                      AND c.entry_type = 'CHARGE'
                      AND c.kind IN ('recurring', 'manual', 'late_fee')
                      AND c.due_date < :today
                      AND p.archived_at IS NULL
                      AND p.party_type = 'tenant'
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries r
                          WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                      )
                )
                SELECT COALESCE(SUM(outstanding), 0)::bigint
                FROM overdue_charges
                WHERE outstanding > 0
                """)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("today", today)
                .query(String.class)
                .list()
                .stream().reduce("", (a, b) -> a + "\n" + b);

        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_3_SUM_OVERDUE_CHARGES:" + explain3);
    }

    private void insertCharge(UUID id, UUID wid, UUID partyId, UUID appId, long amount, String kind, LocalDate dueDate, UUID userId) {
        jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, kind, label, due_date, created_by, created_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :id, 'CHARGE',
                    :amount, 'USD', :kind, 'Charge', :dueDate, :uid, NOW()
                )
                """)
                .param("id", id)
                .param("wid", wid)
                .param("aid", appId)
                .param("pid", partyId)
                .param("amount", amount)
                .param("kind", kind)
                .param("dueDate", dueDate)
                .param("uid", userId)
                .update();
    }

    private void insertCharge(UUID id, UUID partyId, UUID appId, long amount, String kind, LocalDate dueDate) {
        insertCharge(id, workspace1Id, partyId, appId, amount, kind, dueDate, ownerId);
    }

    private void insertPayment(UUID id, UUID chargeId, UUID partyId, UUID appId, long amount, LocalDate paidOn) {
        jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, paid_on, created_by, created_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, 'PAYMENT',
                    :amount, 'USD', :paidOn, :uid, NOW()
                )
                """)
                .param("id", id)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("pid", partyId)
                .param("cid", chargeId)
                .param("amount", amount)
                .param("paidOn", paidOn)
                .param("uid", ownerId)
                .update();
    }

    private void insertReversal(UUID id, UUID chargeId, UUID reversesId, UUID partyId, UUID appId, long amount) {
        jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, reverses_entry_id, created_by, created_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, 'REVERSAL',
                    :amount, 'USD', :revId, :uid, NOW()
                )
                """)
                .param("id", id)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("pid", partyId)
                .param("cid", chargeId)
                .param("revId", reversesId)
                .param("amount", amount)
                .param("uid", ownerId)
                .update();
    }

    private void insertDocRequest(UUID id, UUID wid, UUID aid, UUID pid, String checklistId, String itemId, String label, boolean required, LocalDate dueDate, String status, LocalDate expiresOn) {
        String statusReason = "REJECTED".equals(status) ? "Unreadable document" : null;
        jdbcClient.sql("""
                INSERT INTO document_requests (
                    id, workspace_id, app_id, party_id, checklist_id, item_id,
                    label, required, due_date, status, status_reason, expires_on, version,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, :iid,
                    :label, :required, :dueDate, :status, :statusReason, :expiresOn, 1,
                    :uid, :uid, NOW(), NOW()
                )
                """)
                .param("id", id)
                .param("wid", wid)
                .param("aid", aid)
                .param("pid", pid)
                .param("cid", checklistId)
                .param("iid", itemId)
                .param("label", label)
                .param("required", required)
                .param("dueDate", dueDate)
                .param("status", status)
                .param("statusReason", statusReason)
                .param("expiresOn", expiresOn)
                .param("uid", ownerId)
                .update();
    }

    private void insertCancelledDocRequest(UUID id, UUID wid, UUID aid, UUID pid, String checklistId, String itemId, String label, boolean required, LocalDate dueDate, String status) {
        String statusReason = "REJECTED".equals(status) ? "Unreadable document" : null;
        jdbcClient.sql("""
                INSERT INTO document_requests (
                    id, workspace_id, app_id, party_id, checklist_id, item_id,
                    label, required, due_date, status, status_reason, cancelled_at, version,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, :iid,
                    :label, :required, :dueDate, :status, :statusReason, NOW(), 1,
                    :uid, :uid, NOW(), NOW()
                )
                """)
                .param("id", id)
                .param("wid", wid)
                .param("aid", aid)
                .param("pid", pid)
                .param("cid", checklistId)
                .param("iid", itemId)
                .param("label", label)
                .param("required", required)
                .param("dueDate", dueDate)
                .param("status", status)
                .param("statusReason", statusReason)
                .param("uid", ownerId)
                .update();
    }

    private JsonNode findWidget(JsonNode widgets, String id) {
        for (JsonNode w : widgets) {
            if (id.equals(w.get("id").asText())) {
                return w;
            }
        }
        fail("Widget with id " + id + " not found in response");
        return null;
    }
}
