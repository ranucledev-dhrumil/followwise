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
import java.util.*;

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
    void testFormsAndDashboardSendNoStore() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");

        // GET /forms returns 200 with Cache-Control: private, no-store
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));

        // GET /dashboard returns 200 with Cache-Control: private, no-store
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));

        // GET /forms with PORTAL_USER returns 403 with Cache-Control: private, no-store
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "private, no-store"));

        // GET /dashboard with PORTAL_USER returns 403 with Cache-Control: private, no-store
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    private PartyResponse createTenant(UUID appId, String name, String email, LocalDate leaseEnd) {
        return partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", name)
                        .put("email", email)
                        .put("monthly_rent", 100000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", leaseEnd != null ? leaseEnd.toString() : "2025-01-01")
        );
    }

    private PartyResponse createTenant(UUID appId, String name, String email) {
        return createTenant(appId, name, email, null);
    }

    private UUID insertRawParty(UUID appId, String partyType, String displayName, String dataJson) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO parties (
                    id, workspace_id, app_id, party_type, display_name, data,
                    definition_version, version, created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :id, :wid, :aid, :partyType, :name, CAST(:data AS jsonb),
                    1, 1, :uid, :uid, NOW(), NOW()
                )
                """)
                .param("id", id)
                .param("wid", workspace1Id)
                .param("aid", appId)
                .param("partyType", partyType)
                .param("name", displayName)
                .param("data", dataJson)
                .param("uid", ownerId)
                .update();
        return id;
    }

    @Test
    void testCountAndSumOverdueChargesBoundaries() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        PartyResponse tenant = createTenant(appId, "Tenant Party", "t@example.com");

        PartyResponse archivedTenant = createTenant(appId, "Archived Tenant", "at@example.com");
        partyService.archiveParty(workspace1Id, appId, archivedTenant.id(), ownerId);

        UUID vendorId = insertRawParty(appId, "vendor", "Vendor Party", "{}");

        // 1. Due before today, unpaid -> overdue! (amount 100000, outstanding 100000)
        UUID cBefore = UUID.randomUUID();
        insertCharge(cBefore, tenant.id(), appId, 100000, "recurring", today.minusDays(5));

        // 2. Due before today, partly paid -> overdue for remainder! (amount 80000, paid 30000, outstanding 50000)
        UUID cPartly = UUID.randomUUID();
        insertCharge(cPartly, tenant.id(), appId, 80000, "recurring", today.minusDays(3));
        insertPayment(UUID.randomUUID(), cPartly, tenant.id(), appId, -30000, today.minusDays(2));

        // 3. Due before today, fully paid -> outstanding 0, NOT overdue!
        UUID cFully = UUID.randomUUID();
        insertCharge(cFully, tenant.id(), appId, 60000, "recurring", today.minusDays(4));
        insertPayment(UUID.randomUUID(), cFully, tenant.id(), appId, -60000, today.minusDays(2));

        // 4. Due before today, reversed charge -> NOT overdue!
        UUID cRev = UUID.randomUUID();
        insertCharge(cRev, tenant.id(), appId, 70000, "recurring", today.minusDays(6));
        insertReversal(UUID.randomUUID(), cRev, cRev, tenant.id(), appId, -70000);

        // 5. Due before today, archived party -> NOT overdue!
        UUID cArchived = UUID.randomUUID();
        insertCharge(cArchived, archivedTenant.id(), appId, 40000, "recurring", today.minusDays(2));

        // 6. Due before today, late-fee kind -> overdue! (amount 5000, outstanding 5000)
        UUID cLateFee = UUID.randomUUID();
        insertCharge(cLateFee, tenant.id(), appId, 5000, "late_fee", today.minusDays(1));

        // 7. Due TODAY (due_date == today) -> NOT overdue!
        UUID cToday = UUID.randomUUID();
        insertCharge(cToday, tenant.id(), appId, 90000, "recurring", today);

        // 8. Due after today (future) -> NOT overdue!
        UUID cAfter = UUID.randomUUID();
        insertCharge(cAfter, tenant.id(), appId, 95000, "recurring", today.plusDays(2));

        // 9. Due before today on vendor party
        UUID cVendor = UUID.randomUUID();
        insertCharge(cVendor, vendorId, appId, 20000, "recurring", today.minusDays(3));

        // When partyType = "tenant":
        // Overdue charges: cBefore (100000), cPartly (50000), cLateFee (5000) -> count = 3, sum = 155000
        assertEquals(3L, dashboardStore.countOverdueCharges(workspace1Id, appId, "tenant", today));
        assertEquals(155000L, dashboardStore.sumOverdueCharges(workspace1Id, appId, "tenant", today));

        // When partyType = null (all party types):
        // Adds cVendor (20000) -> count = 4, sum = 175000
        assertEquals(4L, dashboardStore.countOverdueCharges(workspace1Id, appId, null, today));
        assertEquals(175000L, dashboardStore.sumOverdueCharges(workspace1Id, appId, null, today));
    }

    @Test
    void testSumExpectedPeriodMonthBoundaries() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        YearMonth ym = YearMonth.from(today);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        PartyResponse tenant = createTenant(appId, "Tenant Expected", "exp@example.com");

        // Day just outside before: monthStart - 1 day (excluded)
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 10000, "recurring", monthStart.minusDays(1));

        // First day of month: monthStart (included: 20000)
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 20000, "recurring", monthStart);

        // Middle of month (included: 30000)
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 30000, "recurring", monthStart.plusDays(10));

        // Last day of month: monthEnd (included: 40000)
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 40000, "recurring", monthEnd);

        // Day just outside after: monthEnd + 1 day (excluded)
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 50000, "recurring", monthEnd.plusDays(1));

        // Charge in month but reversed (excluded!)
        UUID cRev = UUID.randomUUID();
        insertCharge(cRev, tenant.id(), appId, 60000, "recurring", monthStart.plusDays(5));
        insertReversal(UUID.randomUUID(), cRev, cRev, tenant.id(), appId, -60000);

        // Total expected = 20000 + 30000 + 40000 = 90000
        long sumExpected = dashboardStore.sumExpectedPeriod(workspace1Id, appId, "tenant", monthStart, monthEnd);
        assertEquals(90000L, sumExpected);
    }

    @Test
    void testSumCollectedPeriodUsesPaidOnAndExcludesReversedPayments() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        YearMonth ym = YearMonth.from(today);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        PartyResponse tenant = createTenant(appId, "Tenant Collected", "col@example.com");

        PartyResponse archivedTenant = createTenant(appId, "Archived Tenant Col", "acol@example.com");
        partyService.archiveParty(workspace1Id, appId, archivedTenant.id(), ownerId);

        // 1. Charge due last month, but paid IN THIS MONTH (counts!)
        UUID cLastMonth = UUID.randomUUID();
        insertCharge(cLastMonth, tenant.id(), appId, 50000, "recurring", monthStart.minusMonths(1));
        insertPayment(UUID.randomUUID(), cLastMonth, tenant.id(), appId, -50000, monthStart.plusDays(2));

        // 2. Charge due in this month, paid IN NEXT MONTH (does NOT count this month!)
        UUID cThisMonth = UUID.randomUUID();
        insertCharge(cThisMonth, tenant.id(), appId, 40000, "recurring", monthStart.plusDays(10));
        insertPayment(UUID.randomUUID(), cThisMonth, tenant.id(), appId, -40000, monthEnd.plusDays(1));

        // 3. Payment in this month, but REVERSED (excluded!)
        UUID cRevPay = UUID.randomUUID();
        insertCharge(cRevPay, tenant.id(), appId, 30000, "recurring", monthStart.plusDays(3));
        UUID pRev = UUID.randomUUID();
        insertPayment(pRev, cRevPay, tenant.id(), appId, -30000, monthStart.plusDays(5));
        insertReversal(UUID.randomUUID(), cRevPay, pRev, tenant.id(), appId, 30000);

        // 4. Payment in this month on archived party (excluded!)
        UUID cArch = UUID.randomUUID();
        insertCharge(cArch, archivedTenant.id(), appId, 25000, "recurring", monthStart.plusDays(4));
        insertPayment(UUID.randomUUID(), cArch, archivedTenant.id(), appId, -25000, monthStart.plusDays(6));

        // 5. Payment on first day of month (counts: +10000)
        UUID cStart = UUID.randomUUID();
        insertCharge(cStart, tenant.id(), appId, 10000, "recurring", monthStart);
        insertPayment(UUID.randomUUID(), cStart, tenant.id(), appId, -10000, monthStart);

        // 6. Payment on last day of month (counts: +20000)
        UUID cEnd = UUID.randomUUID();
        insertCharge(cEnd, tenant.id(), appId, 20000, "recurring", monthEnd);
        insertPayment(UUID.randomUUID(), cEnd, tenant.id(), appId, -20000, monthEnd);

        // Total collected = 50000 + 10000 + 20000 = 80000
        long collected = dashboardStore.sumCollectedPeriod(workspace1Id, appId, "tenant", monthStart, monthEnd);
        assertEquals(80000L, collected);
    }

    @Test
    void testListOverduePartiesOrderingTiesAndCap() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        List<UUID> partyIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID pid = insertRawParty(appId, "tenant", "Party " + i, "{}");
            partyIds.add(pid);

            long amount = (i == 0) ? 100000 : 50000;
            UUID cid = UUID.randomUUID();
            insertCharge(cid, pid, appId, amount, "recurring", today.minusDays(10 + i));
            if (i == 0) {
                insertCharge(UUID.randomUUID(), pid, appId, 5000, "late_fee", today.minusDays(5));
            }
        }

        var result = dashboardStore.listOverdueParties(workspace1Id, appId, "tenant", today);
        assertEquals(25L, result.totalCount());
        assertEquals(20, result.items().size());

        // Party 0 has 105000 overdue (2 charges, oldest due date = today - 10)
        assertEquals(partyIds.get(0), result.items().get(0).partyId());
        assertEquals(105000L, result.items().get(0).overdueCents());
        assertEquals(2L, result.items().get(0).overdueChargeCount());
        assertEquals(today.minusDays(10), result.items().get(0).oldestDueDate());

        // Tied parties sorted deterministically by partyId
        for (int i = 1; i < 19; i++) {
            var itemA = result.items().get(i);
            var itemB = result.items().get(i + 1);
            assertEquals(50000L, itemA.overdueCents());
            assertEquals(50000L, itemB.overdueCents());
            assertTrue(itemA.partyId().toString().compareTo(itemB.partyId().toString()) < 0);
        }
    }

    @Test
    void testListExpiringLeasesWindowDays0And60And61() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // Day 0 (today) -> included, daysLeft = 0
        PartyResponse p0 = createTenant(appId, "Lease Day 0", "l0@example.com", today);

        // Day 60 (today + 60) -> included, daysLeft = 60
        PartyResponse p60 = createTenant(appId, "Lease Day 60", "l60@example.com", today.plusDays(60));

        // Day 61 (today + 61) -> excluded!
        createTenant(appId, "Lease Day 61", "l61@example.com", today.plusDays(61));

        // Missing date field -> excluded!
        insertRawParty(appId, "tenant", "Lease Missing Date", "{}");

        // Non-ISO date value -> excluded!
        insertRawParty(appId, "tenant", "Lease Non-ISO", "{\"lease_end\":\"not-a-date\"}");

        // Impossible date value (e.g. 2026-13-45) -> excluded!
        insertRawParty(appId, "tenant", "Lease Impossible", "{\"lease_end\":\"2026-13-45\"}");

        var result = dashboardStore.listExpiringLeases(workspace1Id, appId, "tenant", "lease_end", today, today.plusDays(60));
        assertEquals(2L, result.totalCount());
        assertEquals(2, result.items().size());
        assertEquals(p0.id(), result.items().get(0).partyId());
        assertEquals(0, result.items().get(0).daysLeft());
        assertEquals(p60.id(), result.items().get(1).partyId());
        assertEquals(60, result.items().get(1).daysLeft());
    }

    @Test
    void testListMissingDocsRejectedCancelledAndNullDueDateOrdering() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        PartyResponse p1 = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Party PENDING due-5").put("email", "p1@example.com"));
        PartyResponse p2 = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Party REJECTED due-2").put("email", "p2@example.com"));
        PartyResponse pNull = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Party NULL Due Date").put("email", "pnull@example.com"));
        PartyResponse pCancelled = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Party Cancelled").put("email", "pc@example.com"));

        // p1 has PENDING request due 5 days ago
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, p1.id(), "onboarding_docs", "doc1", "Doc 1", true, today.minusDays(5), "PENDING", null);

        // p2 has REJECTED request due 2 days ago
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, p2.id(), "onboarding_docs", "doc2", "Doc 2", true, today.minusDays(2), "REJECTED", null);

        // pNull has PENDING request with null due date
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, pNull.id(), "onboarding_docs", "doc3", "Doc 3", true, null, "PENDING", null);

        // pCancelled has cancelled request (must be excluded!)
        insertCancelledDocRequest(UUID.randomUUID(), workspace1Id, appId, pCancelled.id(), "onboarding_docs", "doc4", "Doc 4", true, today.minusDays(10), "PENDING");

        var result = dashboardStore.listMissingDocs(workspace1Id, appId, "client");
        assertEquals(3L, result.totalCount());
        assertEquals(3, result.items().size());

        // Order: oldest due date ASC NULLS LAST -> p1 (due-5), then p2 (due-2), then pNull (null due date last)
        assertEquals(p1.id(), result.items().get(0).partyId());
        assertEquals(today.minusDays(5), result.items().get(0).oldestDueDate());
        assertEquals(p2.id(), result.items().get(1).partyId());
        assertEquals(today.minusDays(2), result.items().get(1).oldestDueDate());
        assertEquals(pNull.id(), result.items().get(2).partyId());
        assertNull(result.items().get(2).oldestDueDate());
    }

    @Test
    void testListExpiringDocsWindowDays0And30And31() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        PartyResponse client = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Client Expiring Docs").put("email", "expdocs@example.com"));

        // Day 0: expires today (included, daysLeft = 0)
        UUID r0 = UUID.randomUUID();
        insertDocRequest(r0, workspace1Id, appId, client.id(), "onboarding_docs", "doc0", "Doc Day 0", true, today, "APPROVED", today);

        // Day 30: expires today + 30 (included, daysLeft = 30)
        UUID r30 = UUID.randomUUID();
        insertDocRequest(r30, workspace1Id, appId, client.id(), "onboarding_docs", "doc30", "Doc Day 30", true, today, "APPROVED", today.plusDays(30));

        // Day 31: expires today + 31 (excluded!)
        UUID r31 = UUID.randomUUID();
        insertDocRequest(r31, workspace1Id, appId, client.id(), "onboarding_docs", "doc31", "Doc Day 31", true, today, "APPROVED", today.plusDays(31));

        // PENDING request (only APPROVED counts, so excluded!)
        UUID rPending = UUID.randomUUID();
        insertDocRequest(rPending, workspace1Id, appId, client.id(), "onboarding_docs", "docP", "Doc Pending", true, today, "PENDING", null);

        var result = dashboardStore.listExpiringDocs(workspace1Id, appId, "client", today, today.plusDays(30));
        assertEquals(2L, result.totalCount());
        assertEquals(2, result.items().size());
        assertEquals(r0, result.items().get(0).requestId());
        assertEquals(0, result.items().get(0).daysLeft());
        assertEquals(r30, result.items().get(1).requestId());
        assertEquals(30, result.items().get(1).daysLeft());
    }

    @Test
    void testCompletionPctFloorAndZeroDenominator() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // First test zero denominator: no required requests
        var emptyResult = dashboardStore.completionPctByParty(workspace1Id, appId, "client");
        assertEquals(0L, emptyResult.totalCount());
        assertEquals(0, emptyResult.overallPct());
        assertEquals(0, emptyResult.items().size());

        // Party with 2 approved out of 3 required requests: floor(200 / 3) = 66
        PartyResponse p1 = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Client 2 of 3").put("email", "c23@example.com"));
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, p1.id(), "onboarding_docs", "d1", "Doc 1", true, today, "APPROVED", null);
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, p1.id(), "onboarding_docs", "d2", "Doc 2", true, today, "APPROVED", null);
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, p1.id(), "onboarding_docs", "d3", "Doc 3", true, today, "PENDING", null);

        var result = dashboardStore.completionPctByParty(workspace1Id, appId, "client");
        assertEquals(1L, result.totalCount());
        assertEquals(1, result.items().size());
        assertEquals(66, result.items().get(0).pct());
        assertEquals(66, result.overallPct());
    }

    @Test
    void testCountOverdueDocs() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        PartyResponse client = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Client Overdue Docs").put("email", "overdue@example.com"));

        PartyResponse archivedClient = partyService.createParty(workspace1Id, appId, ownerId, "client",
                jsonMapper.createObjectNode().put("name", "Archived Client").put("email", "archived@example.com"));
        partyService.archiveParty(workspace1Id, appId, archivedClient.id(), ownerId);

        // 1. Required, PENDING, due before today -> counted!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d1", "Doc 1", true, today.minusDays(3), "PENDING", null);

        // 2. Required, REJECTED, due before today -> counted!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d2", "Doc 2", true, today.minusDays(1), "REJECTED", null);

        // 3. Due TODAY -> not overdue!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d3", "Doc 3", true, today, "PENDING", null);

        // 4. Cancelled -> not counted!
        insertCancelledDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d4", "Doc 4", true, today.minusDays(5), "PENDING");

        // 5. Not required (optional) -> not counted!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d5", "Doc 5", false, today.minusDays(4), "PENDING", null);

        // 6. APPROVED -> not counted!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d6", "Doc 6", true, today.minusDays(2), "APPROVED", null);

        // 7. On archived party -> not counted!
        insertDocRequest(UUID.randomUUID(), workspace1Id, appId, archivedClient.id(), "onboarding_docs", "d7", "Doc 7", true, today.minusDays(3), "PENDING", null);

        long count = dashboardStore.countOverdueDocs(workspace1Id, appId, "client", today);
        assertEquals(2L, count);
    }

    @Test
    void testWidgetWithoutPartyTypeCoversAllPartyTypes() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        PartyResponse tenant = createTenant(appId, "Tenant Party", "tenant@example.com");

        UUID vendorId = insertRawParty(appId, "vendor", "Vendor Party", "{}");

        // 1 overdue charge on tenant
        insertCharge(UUID.randomUUID(), tenant.id(), appId, 10000, "recurring", today.minusDays(2));
        // 1 overdue charge on vendor
        insertCharge(UUID.randomUUID(), vendorId, appId, 20000, "recurring", today.minusDays(3));

        // When partyType is null, covers both party types
        long count = dashboardStore.countOverdueCharges(workspace1Id, appId, null, today);
        assertEquals(2L, count);
        long sum = dashboardStore.sumOverdueCharges(workspace1Id, appId, null, today);
        assertEquals(30000L, sum);
    }

    @Test
    void testAllListWidgetsCapAt20WithTotalCount() throws Exception {
        UUID appId = createClientDocApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        for (int i = 0; i < 25; i++) {
            PartyResponse client = partyService.createParty(workspace1Id, appId, ownerId, "client",
                    jsonMapper.createObjectNode().put("name", "Client " + i).put("email", "client" + i + "@example.com"));

            insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "d" + i, "Doc " + i, true, today.minusDays(1), "PENDING", null);
            insertDocRequest(UUID.randomUUID(), workspace1Id, appId, client.id(), "onboarding_docs", "exp" + i, "Exp " + i, true, today.plusDays(10), "APPROVED", today.plusDays(10));
        }

        var missing = dashboardStore.listMissingDocs(workspace1Id, appId, "client");
        assertEquals(25L, missing.totalCount());
        assertEquals(20, missing.items().size());

        var expiring = dashboardStore.listExpiringDocs(workspace1Id, appId, "client", today, today.plusDays(30));
        assertEquals(25L, expiring.totalCount());
        assertEquals(20, expiring.items().size());

        var completion = dashboardStore.completionPctByParty(workspace1Id, appId, "client");
        assertEquals(25L, completion.totalCount());
        assertEquals(20, completion.items().size());
    }

    @Test
    void testDashboardLabelsComeFromFixedTable() {
        assertEquals("Overdue charges", DashboardService.WIDGET_LABELS.get("count_overdue_charges"));
        assertEquals("Overdue amount", DashboardService.WIDGET_LABELS.get("sum_overdue_charges"));
        assertEquals("Expected (selected month)", DashboardService.WIDGET_LABELS.get("sum_expected_period"));
        assertEquals("Collected (selected month)", DashboardService.WIDGET_LABELS.get("sum_collected_period"));
        assertEquals("Overdue parties", DashboardService.WIDGET_LABELS.get("list_overdue_parties"));
        assertEquals("Leases ending soon", DashboardService.WIDGET_LABELS.get("list_expiring_leases"));
        assertEquals("Missing documents", DashboardService.WIDGET_LABELS.get("list_missing_docs"));
        assertEquals("Documents expiring soon", DashboardService.WIDGET_LABELS.get("list_expiring_docs"));
        assertEquals("Document completion", DashboardService.WIDGET_LABELS.get("completion_pct_by_party"));
        assertEquals("Overdue documents", DashboardService.WIDGET_LABELS.get("count_overdue_docs"));
        assertEquals(10, DashboardService.WIDGET_LABELS.size());
    }

    @Test
    void testResponsesContainNoEmailPhoneOrStorageKey() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");

        partyService.createParty(workspace1Id, appId, ownerId, "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "John Secret")
                        .put("email", "john.secret@example.com")
                        .put("phone", "+1 555-123-4567")
                        .put("monthly_rent", 100000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", "2024-12-31")
        );

        MvcResult formsRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        String formsRaw = formsRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(formsRaw.contains("john.secret@example.com"));
        assertFalse(formsRaw.contains("555-123-4567"));
        assertFalse(formsRaw.contains("storageKey"));
        assertFalse(formsRaw.contains("storage_key"));

        MvcResult dashRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        String dashRaw = dashRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(dashRaw.contains("john.secret@example.com"));
        assertFalse(dashRaw.contains("555-123-4567"));
        assertFalse(dashRaw.contains("storageKey"));
        assertFalse(dashRaw.contains("storage_key"));
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
    void testUnavailableReasonCodesAllThree() throws Exception {
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

    record GeneratedFixture(
            List<ExpectedOverdueParty> overdueParties,
            long expectedCountOverdueCharges,
            long expectedSumOverdueCharges,
            long expectedSumExpectedPeriod,
            long expectedSumCollectedPeriod,
            long expectedTotalOverdueParties
    ) {}

    record ExpectedOverdueParty(UUID partyId, long overdueCents, LocalDate oldestDueDate, long overdueChargeCount) {}

    record RawLedgerRow(
            UUID id,
            UUID partyId,
            UUID chargeId,
            String entryType,
            long amountCents,
            String kind,
            String label,
            LocalDate dueDate,
            LocalDate paidOn,
            UUID reversesEntryId
    ) {}

    private GeneratedFixture load20000RowFixture(UUID appId, LocalDate today) throws Exception {
        YearMonth ym = YearMonth.from(today);
        LocalDate mStart = ym.atDay(1);
        LocalDate mEnd = ym.atEndOfMonth();

        UUID[] partyIds = new UUID[500];
        List<RawLedgerRow> entries = new ArrayList<>(20000);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Insert 500 parties
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

            // 2. Generate 40 entries per party (20,000 total)
            for (int i = 0; i < 500; i++) {
                UUID pid = partyIds[i];

                // 0. Overdue recurring charge (unpaid, not reversed)
                UUID cid0 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid0, pid, cid0, "CHARGE", 100000 + i * 100, "recurring", "Rent", today.minusDays(5), null, null));

                // 1. Overdue late fee charge (unpaid, not reversed)
                UUID cid1 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid1, pid, cid1, "CHARGE", 5000, "late_fee", "Late fee", today.minusDays(2), null, null));

                // 2. Future recurring charge (unpaid, not reversed)
                UUID cid2 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid2, pid, cid2, "CHARGE", 120000, "recurring", "Rent future", today.plusDays(10), null, null));

                // 3 & 4. Fully paid charge (charge + payment)
                UUID cid3 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid3, pid, cid3, "CHARGE", 80000, "recurring", "Rent paid", today.minusDays(10), null, null));
                UUID pid4 = UUID.randomUUID();
                entries.add(new RawLedgerRow(pid4, pid, cid3, "PAYMENT", -80000, null, null, null, today, null));

                // 5 & 6. Partly paid charge (charge + partial payment)
                UUID cid5 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid5, pid, cid5, "CHARGE", 90000, "recurring", "Rent partial", today.minusDays(3), null, null));
                UUID pid6 = UUID.randomUUID();
                entries.add(new RawLedgerRow(pid6, pid, cid5, "PAYMENT", -30000, null, null, null, today, null));

                // 7 & 8. Reversed charge (charge + reversal)
                UUID cid7 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid7, pid, cid7, "CHARGE", 70000, "recurring", "Rent reversed", today.minusDays(4), null, null));
                UUID rid8 = UUID.randomUUID();
                entries.add(new RawLedgerRow(rid8, pid, cid7, "REVERSAL", -70000, null, null, null, null, cid7));

                // 9, 10 & 11. Reversed payment (charge + payment + reversal)
                UUID cid9 = UUID.randomUUID();
                entries.add(new RawLedgerRow(cid9, pid, cid9, "CHARGE", 50000, "recurring", "Rent pmt rev", today.minusDays(1), null, null));
                UUID pid10 = UUID.randomUUID();
                entries.add(new RawLedgerRow(pid10, pid, cid9, "PAYMENT", -50000, null, null, null, today, null));
                UUID rid11 = UUID.randomUUID();
                entries.add(new RawLedgerRow(rid11, pid, cid9, "REVERSAL", 50000, null, null, null, null, pid10));

                // 12 to 39. 28 future recurring charges
                for (int k = 0; k < 28; k++) {
                    UUID cidK = UUID.randomUUID();
                    entries.add(new RawLedgerRow(cidK, pid, cidK, "CHARGE", 10000, "recurring", "Future rent", today.plusDays(35 + k), null, null));
                }
            }

            // 3. Batch insert the 20000 ledger entries
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ledger_entries (
                        id, workspace_id, app_id, party_id, charge_id, entry_type,
                        amount_cents, currency, kind, label, due_date, paid_on, reverses_entry_id,
                        created_by, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?, NOW())
                    """)) {
                int count = 0;
                for (RawLedgerRow r : entries) {
                    ps.setObject(1, r.id());
                    ps.setObject(2, workspace1Id);
                    ps.setObject(3, appId);
                    ps.setObject(4, r.partyId());
                    ps.setObject(5, r.chargeId());
                    ps.setString(6, r.entryType());
                    ps.setLong(7, r.amountCents());
                    ps.setString(8, r.kind());
                    ps.setString(9, r.label());
                    ps.setObject(10, r.dueDate());
                    ps.setObject(11, r.paidOn());
                    ps.setObject(12, r.reversesEntryId());
                    ps.setObject(13, ownerId);
                    ps.addBatch();
                    count++;
                    if (count % 2000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }

            conn.commit();
        }

        jdbcClient.sql("ANALYZE ledger_entries").update();
        jdbcClient.sql("ANALYZE parties").update();
        jdbcClient.sql("ANALYZE document_requests").update();

        // 4. Compute expected widget values in Java from generator parameters
        Map<UUID, Long> outstandingByCharge = new HashMap<>();
        Set<UUID> reversedEntries = new HashSet<>();
        for (RawLedgerRow r : entries) {
            if (r.chargeId() != null) {
                outstandingByCharge.merge(r.chargeId(), r.amountCents(), Long::sum);
            }
            if ("REVERSAL".equals(r.entryType()) && r.reversesEntryId() != null) {
                reversedEntries.add(r.reversesEntryId());
            }
        }

        long expectedCountOverdueCharges = 0;
        long expectedSumOverdueCharges = 0;
        Map<UUID, Long> overdueCentsByParty = new HashMap<>();
        Map<UUID, LocalDate> oldestDueDateByParty = new HashMap<>();
        Map<UUID, Long> overdueCountByParty = new HashMap<>();

        for (RawLedgerRow r : entries) {
            if ("CHARGE".equals(r.entryType())
                    && r.dueDate() != null
                    && r.dueDate().isBefore(today)
                    && List.of("recurring", "manual", "late_fee").contains(r.kind())) {
                long out = outstandingByCharge.getOrDefault(r.id(), 0L);
                boolean rev = reversedEntries.contains(r.id());
                if (out > 0 && !rev) {
                    expectedCountOverdueCharges++;
                    expectedSumOverdueCharges += out;

                    overdueCentsByParty.merge(r.partyId(), out, Long::sum);
                    overdueCountByParty.merge(r.partyId(), 1L, Long::sum);
                    oldestDueDateByParty.merge(r.partyId(), r.dueDate(), (d1, d2) -> d1.isBefore(d2) ? d1 : d2);
                }
            }
        }

        long expectedSumExpectedPeriod = 0;
        for (RawLedgerRow r : entries) {
            if ("CHARGE".equals(r.entryType())
                    && r.dueDate() != null
                    && !r.dueDate().isBefore(mStart)
                    && !r.dueDate().isAfter(mEnd)) {
                boolean rev = reversedEntries.contains(r.id());
                if (!rev) {
                    expectedSumExpectedPeriod += r.amountCents();
                }
            }
        }

        long expectedSumCollectedPeriod = 0;
        for (RawLedgerRow r : entries) {
            if ("PAYMENT".equals(r.entryType())
                    && r.paidOn() != null
                    && !r.paidOn().isBefore(mStart)
                    && !r.paidOn().isAfter(mEnd)) {
                boolean rev = reversedEntries.contains(r.id());
                if (!rev) {
                    expectedSumCollectedPeriod += (-r.amountCents());
                }
            }
        }

        List<ExpectedOverdueParty> overdueParties = new ArrayList<>();
        for (UUID pid : overdueCentsByParty.keySet()) {
            overdueParties.add(new ExpectedOverdueParty(
                    pid,
                    overdueCentsByParty.get(pid),
                    oldestDueDateByParty.get(pid),
                    overdueCountByParty.get(pid)
            ));
        }
        overdueParties.sort(Comparator.comparing(ExpectedOverdueParty::overdueCents).reversed()
                .thenComparing(p -> p.partyId().toString()));

        return new GeneratedFixture(
                overdueParties,
                expectedCountOverdueCharges,
                expectedSumOverdueCharges,
                expectedSumExpectedPeriod,
                expectedSumCollectedPeriod,
                overdueParties.size()
        );
    }

    @Test
    void testPerformanceFixtureValuesMatchGenerator() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        GeneratedFixture fixture = load20000RowFixture(appId, today);

        long start = System.currentTimeMillis();
        DashboardResponse response = dashboardService.getDashboard(workspace1Id, appId, ownerId, null);
        long elapsed = System.currentTimeMillis() - start;

        // Assert widget values match Java generator calculations
        var countOverdueWidget = (DashboardResponse.CountData) findWidgetResult(response.widgets(), "count_overdue").data();
        assertEquals(fixture.expectedCountOverdueCharges(), countOverdueWidget.count());

        assertEquals(fixture.expectedSumOverdueCharges(), dashboardStore.sumOverdueCharges(workspace1Id, appId, "tenant", today));

        var sumExpectedWidget = (DashboardResponse.SumData) findWidgetResult(response.widgets(), "sum_expected").data();
        assertEquals(fixture.expectedSumExpectedPeriod(), sumExpectedWidget.cents());

        var sumCollectedWidget = (DashboardResponse.SumData) findWidgetResult(response.widgets(), "sum_collected").data();
        assertEquals(fixture.expectedSumCollectedPeriod(), sumCollectedWidget.cents());

        @SuppressWarnings("unchecked")
        var listOverdueWidget = (DashboardResponse.ListData<DashboardResponse.OverduePartyItem>) findWidgetResult(response.widgets(), "list_overdue").data();
        assertEquals(fixture.expectedTotalOverdueParties(), listOverdueWidget.totalCount());
        assertEquals(20, listOverdueWidget.items().size());

        for (int k = 0; k < 3; k++) {
            ExpectedOverdueParty expectedItem = fixture.overdueParties().get(k);
            DashboardResponse.OverduePartyItem actualItem = listOverdueWidget.items().get(k);
            assertEquals(expectedItem.partyId(), actualItem.partyId());
            assertEquals(expectedItem.overdueCents(), actualItem.overdueCents());
            assertEquals(expectedItem.oldestDueDate(), actualItem.oldestDueDate());
            assertEquals(expectedItem.overdueChargeCount(), actualItem.overdueChargeCount());
        }

        System.out.println("20000_ROWS_PERFORMANCE_REPORT: Elapsed time = " + elapsed + " ms");
        assertTrue(elapsed < 1500, "Dashboard on 20,000 ledger entries must complete under 1500 ms, took " + elapsed + " ms");
    }

    @Test
    void testDashboardPerformance20000Rows() throws Exception {
        UUID appId = createRentalApp(workspace1Id, "America/New_York");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        load20000RowFixture(appId, today);

        long start = System.currentTimeMillis();
        DashboardResponse response = dashboardService.getDashboard(workspace1Id, appId, ownerId, null);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("20000_ROWS_PERFORMANCE_REPORT: Elapsed time = " + elapsed + " ms");
        assertTrue(elapsed < 1500, "Dashboard on 20,000 ledger entries must complete under 1500 ms, took " + elapsed + " ms");
        assertEquals(5, response.widgets().size());

        // Report SELECT version()
        String pgVersion = jdbcClient.sql("SELECT version()").query(String.class).single();
        System.out.println("TESTCONTAINERS_POSTGRES_VERSION: " + pgVersion);

        // Run EXPLAIN (ANALYZE, BUFFERS) on the three heaviest queries using DashboardStore's QuerySpec
        // 1. list_overdue_parties
        DashboardStore.QuerySpec q1 = dashboardStore.listOverduePartiesQuery(workspace1Id, appId, "tenant", today);
        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_1_LIST_OVERDUE_PARTIES:\n" + runExplain(q1));

        // 2. sum_expected_period
        YearMonth ym = YearMonth.from(today);
        DashboardStore.QuerySpec q2 = dashboardStore.sumExpectedPeriodQuery(workspace1Id, appId, "tenant", ym.atDay(1), ym.atEndOfMonth());
        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_2_SUM_EXPECTED_PERIOD:\n" + runExplain(q2));

        // 3. sum_overdue_charges
        DashboardStore.QuerySpec q3 = dashboardStore.sumOverdueChargesQuery(workspace1Id, appId, "tenant", today);
        System.out.println("EXPLAIN_ANALYZE_HEAVIEST_3_SUM_OVERDUE_CHARGES:\n" + runExplain(q3));
    }

    private String runExplain(DashboardStore.QuerySpec spec) {
        var clientSpec = jdbcClient.sql("EXPLAIN (ANALYZE, BUFFERS)\n" + spec.sql());
        spec.params().forEach(clientSpec::param);
        return clientSpec.query(String.class).list().stream().reduce("", (a, b) -> a + "\n" + b);
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

    private DashboardResponse.WidgetResult findWidgetResult(List<DashboardResponse.WidgetResult> widgets, String id) {
        for (DashboardResponse.WidgetResult w : widgets) {
            if (id.equals(w.id())) {
                return w;
            }
        }
        fail("Widget with id " + id + " not found in response");
        return null;
    }
}
