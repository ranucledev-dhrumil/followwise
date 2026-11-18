package com.followwise.reminder;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.ledger.LedgerService;
import com.followwise.party.PartyResponse;
import com.followwise.party.PartyService;
import com.followwise.security.TokenService;
import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Import(ReminderApiTest.TestReminderClockConfig.class)
class ReminderApiTest extends AbstractIntegrationTest {

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
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final MutableClock MUTABLE_CLOCK = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

    @TestConfiguration
    static class TestReminderClockConfig {
        @Bean
        @Primary
        public Clock reminderTestClock() {
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
    private com.followwise.party.PartyStore partyStore;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ReminderEngineService reminderEngineService;

    @Autowired
    private ReminderProperties reminderProperties;

    @Autowired
    private com.followwise.app.AppStore appStore;

    @Autowired
    private ReminderEventStore reminderEventStore;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspace1Id;
    private UUID workspace2Id;
    private UUID appRentalId;
    private UUID appDocId;
    private UUID tenant1Id;
    private UUID client1Id;

    private UUID ownerId;
    private UUID staffId;
    private UUID portalId;
    private UUID nonMemberId;

    private String ownerToken;
    private String staffToken;
    private String portalToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() throws Exception {
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T12:00:00Z"));
        TestDbHelper.cleanDatabase(dataSource);

        // Reset default properties
        reminderProperties.setRemindersEnabled(true);
        reminderProperties.setSendHour(8);
        reminderProperties.setCatchupDays(7);
        reminderProperties.setMaxRemindersPerAppRun(100);
        reminderProperties.setMaxOutboxBacklog(500);
        reminderProperties.setMaxRemindersPerPartyPerDay(2);
        reminderProperties.setMaxRemindersPerOwnerPerDay(10);
        reminderProperties.setMaxCandidatesScannedPerRule(5000);
        reminderProperties.setPageSize(200);

        Instant now = Instant.now();
        ownerId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        portalId = UUID.randomUUID();
        nonMemberId = UUID.randomUUID();

        // Save users with verified emails
        User u1 = new User(ownerId, "owner@example.com", "hash", now);
        u1.setEmailVerifiedAt(now);
        userRepository.save(u1);
        User u2 = new User(staffId, "staff@example.com", "hash", now);
        u2.setEmailVerifiedAt(now);
        userRepository.save(u2);
        User u3 = new User(portalId, "portal@example.com", "hash", now);
        u3.setEmailVerifiedAt(now);
        userRepository.save(u3);
        User u4 = new User(nonMemberId, "nonmember@example.com", "hash", now);
        u4.setEmailVerifiedAt(now);
        userRepository.save(u4);

        Workspace ws1 = new Workspace(UUID.randomUUID(), "Property Co", now);
        Workspace ws2 = new Workspace(UUID.randomUUID(), "Other Co", now);
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

        // Create rental app
        AppResponse rentalApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental App", "UTC"));
        appRentalId = rentalApp.id();

        // Create tenant party with default lease_end far in future (2026-12-31)
        PartyResponse t1 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {
                          "name": "Alice Tenant",
                          "email": "alice@tenant.com",
                          "monthly_rent": 100000,
                          "due_day": 1,
                          "lease_start": "2026-01-01",
                          "lease_end": "2026-12-31"
                        }
                        """));
        tenant1Id = t1.id();

        // Create document app
        AppResponse docApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "Doc App", "UTC"));
        appDocId = docApp.id();

        // Create client party
        PartyResponse c1 = partyService.createParty(workspace1Id, appDocId, ownerId, "client",
                jsonMapper.readTree("""
                        {"name": "Bob Client", "email": "bob@client.com"}
                        """));
        client1Id = c1.id();
    }

    @AfterEach
    void tearDown() {
        TestDbHelper.cleanDatabase(dataSource);
    }

    private void postCharge(UUID wid, UUID aid, UUID pid, String label, long amountCents, LocalDate dueDate) {
        ledgerService.createCharge(wid, aid, pid, ownerId, label, amountCents, dueDate, null, null, "/charges");
    }

    private void recordPayment(UUID wid, UUID aid, UUID chargeId, long amountCents, LocalDate paidOn) {
        ledgerService.recordPayment(wid, aid, chargeId, ownerId, amountCents, paidOn, null, null, "/payments");
    }

    @Test
    void testTriggers_ChargeDue_And_LeaseEnd() {
        // Today is 2026-06-01.
        // Set catchupDays to 0 so only today's exact scheduled reminders fire
        reminderProperties.setCatchupDays(0);

        // 1. Post a charge due 2026-06-01 -> rent_reminder_due (offset 0) fires for tenant
        postCharge(workspace1Id, appRentalId, tenant1Id, "June Rent", 100000L, LocalDate.of(2026, 6, 1));

        // 2. Set tenant lease_end to 2026-07-31. Offset is -60 -> scheduledFor is 2026-06-01 -> lease_ending_owner_60 fires for owner!
        jdbcClient.sql("""
                UPDATE parties
                SET data = jsonb_set(data, '{lease_end}', '"2026-07-31"')
                WHERE id = :pid
                """)
                .param("pid", tenant1Id)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        assertEquals(2, res.queued());

        // Verify reminder_events table has rows
        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid AND status = 'QUEUED'")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertEquals(2, count);

        // Verify email_outbox has rows
        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE kind = 'REMINDER'")
                .query(Integer.class)
                .single();
        assertEquals(2, outboxCount);
    }

    @Test
    void testTriggers_ChargeOverdue() {
        // Rent due on 2026-05-25 (7 days ago).
        // Trigger rent_overdue_owner_7 (offset 7) -> scheduledFor = 2026-05-25 + 7 days = 2026-06-01!
        postCharge(workspace1Id, appRentalId, tenant1Id, "May Rent", 80000L, LocalDate.of(2026, 5, 25));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res.queued() >= 1);

        // Verify outbox subject
        String subject = jdbcClient.sql("SELECT subject FROM email_outbox WHERE to_address = 'owner@example.com'")
                .query(String.class)
                .single();
        assertTrue(subject.contains("Overdue: Alice Tenant owes $800.00"));
    }

    @Test
    void testTriggers_DocumentDue_And_Expiry() {
        // Today is 2026-06-01. Set catchupDays to 0 so only exact today's reminders fire
        reminderProperties.setCatchupDays(0);

        // In CDT template:
        // doc_due_today: offset 0 -> due on 2026-06-01 -> scheduledFor = 2026-06-01
        UUID reqId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, due_date, status, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'tax_doc', 'Tax Document', true, '2026-06-01', 'PENDING', :uid, :uid)
                """)
                .param("id", reqId)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appDocId, false, false);
        assertEquals(1, res.queued());

        String body = jdbcClient.sql("SELECT body_text FROM email_outbox WHERE to_address = 'bob@client.com'")
                .query(String.class)
                .single();
        assertTrue(body.contains("Property Co is waiting for Tax Document, due today (2026-06-01)"));
    }

    @Test
    void testCatchupWindow() {
        // Today is 2026-06-01. Catchup days = 7.
        // A charge due on 2026-05-28 (scheduledFor = 2026-05-28, 4 days ago) is INSIDE window.
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Inside Window", 50000L, LocalDate.of(2026, 5, 28));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res.queued() >= 1);

        // Clean db and setup a charge due on 2026-05-10 (scheduledFor >= 15 days ago) which is OUTSIDE catchup window (7 days).
        TestDbHelper.cleanDatabase(dataSource);
        setUpTenant();
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Outside Window", 50000L, LocalDate.of(2026, 5, 10));

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res2.queued());
    }

    @Test
    void testSendHourGate() {
        // App timezone is UTC. If local hour < sendHour (sendHour=8), processApp returns 0 queued.
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T07:00:00Z"));
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent 7am", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());

        // Now advance clock to 09:00 UTC (hour 9 >= sendHour 8)
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T09:00:00Z"));
        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res2.queued() >= 1);
    }

    @Test
    void testIdempotency() {
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent June", 50000L, LocalDate.of(2026, 6, 1));

        // First run
        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res1.queued() >= 1);

        // Second run
        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res2.queued());
        assertTrue(res2.skippedExisting() >= 1);

        // Verify outbox wasn't duplicated
        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE kind = 'REMINDER'")
                .query(Integer.class)
                .single();
        assertEquals(res1.queued(), outboxCount);
    }

    @Test
    void testStateRechecks_PaidCharge_And_ArchivedParty() {
        // 1. Post charge and pay it in full
        postCharge(workspace1Id, appRentalId, tenant1Id, "Paid Rent", 50000L, LocalDate.of(2026, 6, 1));
        UUID chargeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Paid Rent'").query(UUID.class).single();
        recordPayment(workspace1Id, appRentalId, chargeId, 50000L, LocalDate.of(2026, 6, 1));

        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        // Paid charge must not produce a reminder
        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE subject_id = :cid")
                .param("cid", chargeId)
                .query(Integer.class)
                .single();
        assertEquals(0, count);

        // 2. Post charge first, then archive party
        postCharge(workspace1Id, appRentalId, tenant1Id, "Archived Tenant Rent", 50000L, LocalDate.of(2026, 6, 1));
        UUID archivedChargeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Archived Tenant Rent'").query(UUID.class).single();

        jdbcClient.sql("UPDATE parties SET archived_at = NOW() WHERE id = :pid")
                .param("pid", tenant1Id)
                .update();

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        Integer archivedCount = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE subject_id = :cid")
                .param("cid", archivedChargeId)
                .query(Integer.class)
                .single();
        assertEquals(0, archivedCount);
    }

    @Test
    void testSkipReasons_And_Events() {
        // 1. PAUSED skip reason
        partyStore.pauseReminders(workspace1Id, appRentalId, tenant1Id, 1, Instant.now());
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Paused", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res.skippedPaused() >= 1);

        String skipReason = jdbcClient.sql("SELECT skip_reason FROM reminder_events WHERE party_id = :pid AND status = 'SKIPPED'")
                .param("pid", tenant1Id)
                .query(String.class)
                .list()
                .get(0);
        assertEquals("PAUSED", skipReason);

        // 2. NO_EMAIL skip reason
        partyStore.resumeReminders(workspace1Id, appRentalId, tenant1Id, 2, Instant.now());
        jdbcClient.sql("UPDATE parties SET data = '{\"name\": \"No Email Guy\", \"monthly_rent\": 100000, \"due_day\": 1, \"lease_start\": \"2026-01-01\", \"lease_end\": \"2026-07-31\"}'::jsonb WHERE id = :pid")
                .param("pid", tenant1Id)
                .update();

        // Clear reminder events with session_replication_role
        jdbcClient.sql("SET session_replication_role = replica; DELETE FROM reminder_events; SET session_replication_role = DEFAULT;").update();
        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res2.skippedNoEmail() >= 1);

        // 3. UNVERIFIED_OWNER skip reason
        jdbcClient.sql("UPDATE users SET email_verified_at = NULL WHERE id = :uid")
                .param("uid", ownerId)
                .update();
        jdbcClient.sql("SET session_replication_role = replica; DELETE FROM reminder_events; SET session_replication_role = DEFAULT;").update();

        var res3 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res3.skippedUnverifiedOwner() >= 1);
    }

    @Test
    void testRemindersDisabled() {
        reminderProperties.setRemindersEnabled(false);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());
        assertFalse(res.remindersEnabled());
    }

    @Test
    void testMaxOutboxBacklog() {
        // Insert 501 QUEUED rows in email_outbox to exceed 500 backlog cap
        for (int i = 0; i < 501; i++) {
            jdbcClient.sql("""
                    INSERT INTO email_outbox (id, to_address, subject, body_text, status, kind)
                    VALUES (:id, 'x@test.com', 'Sub', 'Body', 'QUEUED', 'VERIFY_EMAIL')
                    """)
                    .param("id", UUID.randomUUID())
                    .update();
        }

        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());
        assertTrue(res.blockedByBacklog());
    }

    @Test
    void testReplyToHeader_And_OutboxPriority() {
        reminderProperties.setCatchupDays(0);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent", 50000L, LocalDate.of(2026, 6, 1));

        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        // Tenant reminder row has owner's email as reply_to
        String replyTo = jdbcClient.sql("SELECT reply_to FROM email_outbox WHERE to_address = 'alice@tenant.com'")
                .query(String.class)
                .single();
        assertEquals("owner@example.com", replyTo);
    }

    @Test
    void testPreviewDryRunEndpoint() throws Exception {
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent June", 50000L, LocalDate.of(2026, 6, 1));

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(true))
                .andExpect(jsonPath("$.blockedByBacklog").value(false))
                .andExpect(jsonPath("$.items", not(empty())));

        // Dry-run must write NO rows
        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(0, count);
    }

    @Test
    void testPauseAndResumeEndpoints() throws Exception {
        // 1. Pause party
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/pause")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersPaused").value(true))
                .andExpect(jsonPath("$.version").value(2));

        // 1b. No-op pause when already paused -> 200 OK without version bump
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/pause")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersPaused").value(true))
                .andExpect(jsonPath("$.version").value(2));

        // 2. Stale version 409
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/pause")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 1}"))
                .andExpect(status().isConflict());

        // 3. Resume party
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/resume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersPaused").value(false))
                .andExpect(jsonPath("$.version").value(3));

        // 3b. No-op resume when not paused -> 200 OK without version bump
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/resume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersPaused").value(false))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void testPauseAndResumeMalformedJsonExcludesSecretMarker() throws Exception {
        String malformedWithSecret = "{\"expectedVersion\": 1, \"corrupt\": SECRET_MARKER_123";

        var pauseRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/pause")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedWithSecret))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(pauseRes.contains("SECRET_MARKER_123"), "Pause error response must not leak secret marker");

        var resumeRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/parties/" + tenant1Id + "/reminders/resume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedWithSecret))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(resumeRes.contains("SECRET_MARKER_123"), "Resume error response must not leak secret marker");
    }

    @Test
    void testEndpointPermissions() throws Exception {
        // Portal user cannot access preview -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Portal user cannot access reminder-events -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminder-events")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Staff user has VIEW_REMINDERS -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // Cross-workspace access -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void testImmutabilityTrigger() {
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent", 50000L, LocalDate.of(2026, 6, 1));
        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        // Attempting direct UPDATE on reminder_events fails
        assertThrows(org.springframework.dao.DataAccessException.class, () -> {
            jdbcClient.sql("UPDATE reminder_events SET status = 'SKIPPED'").update();
        });

        // Attempting direct DELETE on reminder_events fails
        assertThrows(org.springframework.dao.DataAccessException.class, () -> {
            jdbcClient.sql("DELETE FROM reminder_events").update();
        });
    }

    @Test
    void testAllFiveTriggersWithOffsetsAndScheduledFor() {
        // Today is 2026-06-01 UTC. Set catchupDays to 0 so only exact today's reminders fire.
        reminderProperties.setCatchupDays(0);

        // Trigger 1: charge.due (rent_reminder_due, offset 0) -> due on 2026-06-01 -> scheduled_for = 2026-06-01
        postCharge(workspace1Id, appRentalId, tenant1Id, "Due Rent", 100000L, LocalDate.of(2026, 6, 1));

        // Trigger 2: charge.overdue (rent_overdue_owner_7, offset 7) -> due on 2026-05-25 -> scheduled_for = 2026-05-25 + 7 = 2026-06-01
        postCharge(workspace1Id, appRentalId, tenant1Id, "Overdue Rent", 80000L, LocalDate.of(2026, 5, 25));

        // Trigger 3: document.due (doc_due_today, offset 0) -> due on 2026-06-01 -> scheduled_for = 2026-06-01
        UUID docReqId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, due_date, status, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'tax_doc', 'Tax Document', true, '2026-06-01', 'PENDING', :uid, :uid)
                """)
                .param("id", docReqId)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        // Trigger 4: document.expiry (doc_expiry_30, offset -30) -> expires_on 2026-07-01 -> scheduled_for = 2026-07-01 - 30 = 2026-06-01
        UUID expiryReqId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, status, expires_on, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'insurance', 'Insurance', true, 'APPROVED', '2026-07-01', :uid, :uid)
                """)
                .param("id", expiryReqId)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        // Trigger 5: lease.end (lease_ending_owner_60, offset -60) -> lease_end 2026-07-31 -> scheduled_for = 2026-07-31 - 60 = 2026-06-01
        jdbcClient.sql("UPDATE parties SET data = jsonb_set(data, '{lease_end}', '\"2026-07-31\"') WHERE id = :pid")
                .param("pid", tenant1Id)
                .update();

        // Process rental app -> triggers 1, 2, 5
        var rentalRes = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(4, rentalRes.queued());

        // Process doc app -> triggers 3, 4
        var docRes = reminderEngineService.processApp(workspace1Id, appDocId, false, false);
        assertEquals(2, docRes.queued());

        // Assert all 6 events have scheduled_for = 2026-06-01 exactly
        List<LocalDate> scheduledDates = jdbcClient.sql("SELECT scheduled_for FROM reminder_events ORDER BY id")
                .query(LocalDate.class)
                .list();
        assertEquals(6, scheduledDates.size());
        for (LocalDate d : scheduledDates) {
            assertEquals(LocalDate.of(2026, 6, 1), d);
        }
    }

    @Test
    void testDocumentDueWithOwnerRecipient() {
        reminderProperties.setCatchupDays(0);

        // doc_overdue_owner_7: document.due with offset 7, recipient owner
        // due_date = 2026-05-25 -> scheduled_for = 2026-05-25 + 7 = 2026-06-01
        UUID docReqId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, due_date, status, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'passport', 'Passport', true, '2026-05-25', 'PENDING', :uid, :uid)
                """)
                .param("id", docReqId)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appDocId, false, false);
        assertTrue(res.queued() >= 1);

        // Verify reminder_events has recipient_kind = 'OWNER' and recipient_user_id = ownerId
        var event = jdbcClient.sql("SELECT recipient_kind, recipient_user_id, rule_id FROM reminder_events WHERE subject_id = :sid")
                .param("sid", docReqId)
                .query((rs, rowNum) -> List.of(rs.getString("recipient_kind"), rs.getString("recipient_user_id"), rs.getString("rule_id")))
                .single();
        assertEquals("OWNER", event.get(0));
        assertEquals(ownerId.toString(), event.get(1));
        assertEquals("doc_overdue_owner_7", event.get(2));

        // Verify outbox has owner address with null reply_to
        var outbox = jdbcClient.sql("SELECT to_address, reply_to, subject FROM email_outbox WHERE kind = 'REMINDER' AND to_address = 'owner@example.com'")
                .query((rs, rowNum) -> List.of(rs.getString("to_address"), rs.getString("reply_to") == null ? "NULL" : rs.getString("reply_to"), rs.getString("subject")))
                .single();
        assertEquals("owner@example.com", outbox.get(0));
        assertEquals("NULL", outbox.get(1));
        assertTrue(outbox.get(2).contains("Overdue: Passport from Bob Client"));
    }

    @Test
    void testLateFeeChargeReminded() {
        reminderProperties.setCatchupDays(0);

        // Create charge and update kind to late_fee
        postCharge(workspace1Id, appRentalId, tenant1Id, "Late Fee $25", 2500L, LocalDate.of(2026, 6, 1));
        jdbcClient.sql("SET session_replication_role = replica; UPDATE ledger_entries SET kind = 'late_fee' WHERE label = 'Late Fee $25'; SET session_replication_role = DEFAULT;").update();

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res.queued());

        String subject = jdbcClient.sql("SELECT subject FROM email_outbox WHERE to_address = 'alice@tenant.com'")
                .query(String.class)
                .single();
        assertTrue(subject.contains("Late Fee $25 of $25.00 is due today"));
    }

    @Test
    void testCatchUpWindowBoundaries() {
        // Today is 2026-06-01. Window [today - 2, today] = [2026-05-30, 2026-06-01]
        reminderProperties.setCatchupDays(2);

        // Inside window: today (2026-06-01)
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Today", 10000L, LocalDate.of(2026, 6, 1));

        // Inside window: exactly lower boundary (2026-05-30)
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Lower Boundary", 20000L, LocalDate.of(2026, 5, 30));

        // One day outside window: 2026-05-20 (scheduled_for < today - 2)
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Outside Window", 30000L, LocalDate.of(2026, 5, 20));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res.queued());

        // Verify the 2026-05-29 charge has NO event row created
        UUID outsideChargeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Rent Outside Window'").query(UUID.class).single();
        Integer eventCount = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE subject_id = :cid")
                .param("cid", outsideChargeId)
                .query(Integer.class)
                .single();
        assertEquals(0, eventCount);
    }

    @Test
    void testSendHourGateAcrossTimeZones() {
        reminderProperties.setSendHour(9);

        // App in America/New_York (UTC-4 in summer)
        AppResponse nyApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "NY App", "America/New_York"));
        PartyResponse nyTenant = partyService.createParty(workspace1Id, nyApp.id(), ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "NY Tenant", "email": "ny@tenant.com", "monthly_rent": 100000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        postCharge(workspace1Id, nyApp.id(), nyTenant.id(), "NY Rent", 100000L, LocalDate.of(2026, 6, 1));

        // App in Asia/Tokyo (UTC+9)
        AppResponse tokyoApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Tokyo App", "Asia/Tokyo"));
        PartyResponse tokyoTenant = partyService.createParty(workspace1Id, tokyoApp.id(), ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tokyo Tenant", "email": "tokyo@tenant.com", "monthly_rent": 100000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        postCharge(workspace1Id, tokyoApp.id(), tokyoTenant.id(), "Tokyo Rent", 100000L, LocalDate.of(2026, 6, 1));

        // At 11:00 UTC:
        // New York local time is 07:00 (< 9:00 -> blocked)
        // Tokyo local time is 20:00 (>= 9:00 -> passed)
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T11:00:00Z"));

        var nyRes = reminderEngineService.processApp(workspace1Id, nyApp.id(), false, false);
        assertEquals(0, nyRes.queued());

        var tokyoRes = reminderEngineService.processApp(workspace1Id, tokyoApp.id(), false, false);
        assertTrue(tokyoRes.queued() >= 1);
    }

    @Test
    void testSecondRunCreatesNothing() {
        postCharge(workspace1Id, appRentalId, tenant1Id, "June Rent Second", 50000L, LocalDate.of(2026, 6, 1));

        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res1.queued() >= 1);

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res2.queued());
        assertTrue(res2.skippedExisting() >= 1);

        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE kind = 'REMINDER'").query(Integer.class).single();
        assertEquals(res1.queued(), outboxCount);
    }

    @Test
    void testConcurrentRunsProduceNoDuplicateEvents() throws Exception {
        reminderProperties.setCatchupDays(0);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Concurrent Rent", 50000L, LocalDate.of(2026, 6, 1));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> reminderEngineService.processApp(workspace1Id, appRentalId, false, false));
            var f2 = pool.submit(() -> reminderEngineService.processApp(workspace1Id, appRentalId, false, false));

            var r1 = f1.get();
            var r2 = f2.get();

            assertEquals(1, r1.queued() + r2.queued(), "Exactly one run queues the reminder");

            Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid AND status = 'QUEUED'")
                    .param("aid", appRentalId)
                    .query(Integer.class)
                    .single();
            assertEquals(1, count);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void testPartlyPaidChargeReminded() {
        reminderProperties.setCatchupDays(0);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Partial Rent", 100000L, LocalDate.of(2026, 6, 1));
        UUID chargeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Partial Rent'").query(UUID.class).single();

        // Pay $40 of $100 -> outstanding is $60 (> 0)
        recordPayment(workspace1Id, appRentalId, chargeId, 40000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res.queued());

        String subject = jdbcClient.sql("SELECT subject FROM email_outbox WHERE to_address = 'alice@tenant.com'")
                .query(String.class)
                .single();
        assertTrue(subject.contains("Partial Rent of $600.00 is due today"));
    }

    @Test
    void testPaidReversedOrApprovedCreatesNothing() {
        reminderProperties.setCatchupDays(0);

        // 1. Fully paid charge
        postCharge(workspace1Id, appRentalId, tenant1Id, "Fully Paid", 50000L, LocalDate.of(2026, 6, 1));
        UUID paidId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Fully Paid'").query(UUID.class).single();
        recordPayment(workspace1Id, appRentalId, paidId, 50000L, LocalDate.of(2026, 6, 1));

        // 2. Reversed charge
        postCharge(workspace1Id, appRentalId, tenant1Id, "Reversed Charge", 40000L, LocalDate.of(2026, 6, 1));
        UUID revId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE label = 'Reversed Charge'").query(UUID.class).single();
        ledgerService.reverseEntry(workspace1Id, appRentalId, revId, ownerId, "error", null, "/charges/reverse");

        // 3. Approved doc request (not expiring)
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, due_date, status, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'w9', 'W9', true, '2026-06-01', 'APPROVED', :uid, :uid)
                """)
                .param("id", UUID.randomUUID())
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        // 4. Received doc request (not pending)
        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, due_date, status, created_by, updated_by)
                VALUES (:id, :wid, :aid, :pid, 'onboarding', 'photo', 'Photo', true, '2026-06-01', 'RECEIVED', :uid, :uid)
                """)
                .param("id", UUID.randomUUID())
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("pid", client1Id)
                .param("uid", ownerId)
                .update();

        var rentalRes = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, rentalRes.queued());

        var docRes = reminderEngineService.processApp(workspace1Id, appDocId, false, false);
        assertEquals(0, docRes.queued());

        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(0, count);
    }

    @Test
    void testSkipReasonsWrittenAndFinal() {
        reminderProperties.setCatchupDays(0);

        // 1. PAUSED
        partyStore.pauseReminders(workspace1Id, appRentalId, tenant1Id, 1, Instant.now());
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Paused Final", 50000L, LocalDate.of(2026, 6, 1));
        var r1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(r1.skippedPaused() >= 1);

        String skipPaused = jdbcClient.sql("SELECT skip_reason FROM reminder_events WHERE status = 'SKIPPED' AND party_id = :pid")
                .param("pid", tenant1Id).query(String.class).list().get(0);
        assertEquals("PAUSED", skipPaused);

        // 2. NO_EMAIL
        PartyResponse noEmailParty = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "No Email Final", "email": "valid@email.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        jdbcClient.sql("UPDATE parties SET data = jsonb_set(data, '{email}', '\"\"') WHERE id = :pid")
                .param("pid", noEmailParty.id()).update();
        postCharge(workspace1Id, appRentalId, noEmailParty.id(), "Rent No Email Final", 50000L, LocalDate.of(2026, 6, 1));
        var r2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(r2.skippedNoEmail() >= 1);

        // 3. UNVERIFIED_OWNER is written and stays final after owner verifies
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3 Final", "email": "t3final@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        postCharge(workspace1Id, appRentalId, p3.id(), "Rent Unverified Final", 50000L, LocalDate.of(2026, 6, 1));
        // Unverify owner
        jdbcClient.sql("UPDATE users SET email_verified_at = NULL WHERE id = :uid").param("uid", ownerId).update();

        var r3 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(r3.skippedUnverifiedOwner() >= 1);

        // Verify owner now
        jdbcClient.sql("UPDATE users SET email_verified_at = NOW() WHERE id = :uid").param("uid", ownerId).update();
        var r4 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        // Previously SKIPPED candidate remains final; it is skippedExisting, never queued
        assertEquals(0, r4.queued());
        assertTrue(r4.skippedExisting() >= 1);
    }

    @Test
    void testRemindersDisabledGlobalSwitch() throws Exception {
        reminderProperties.setRemindersEnabled(false);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Disabled Switch", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());
        assertFalse(res.remindersEnabled());

        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(0, count);

        // Preview still works and returns remindersEnabled: false
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(false))
                .andExpect(jsonPath("$.items", not(empty())));
    }

    @Test
    void testPerRunCapStopsAppRun() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(2);

        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        // Create 3 charges due today across distinct parties
        postCharge(workspace1Id, appRentalId, tenant1Id, "Charge Cap 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p2.id(), "Charge Cap 2", 20000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p3.id(), "Charge Cap 3", 30000L, LocalDate.of(2026, 6, 1));

        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res1.queued());
        assertTrue(res1.capReached());

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res2.queued());

        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(3, count);
    }

    @Test
    void testRunCapWritesExactlyCapThenContinuesNextRun() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(2);

        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p4 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 4", "email": "t4@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p5 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 5", "email": "t5@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        postCharge(workspace1Id, appRentalId, tenant1Id, "RunCap Charge 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p2.id(), "RunCap Charge 2", 20000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p3.id(), "RunCap Charge 3", 30000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p4.id(), "RunCap Charge 4", 40000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p5.id(), "RunCap Charge 5", 50000L, LocalDate.of(2026, 6, 1));

        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res1.queued());
        assertTrue(res1.capReached());

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res2.queued());
        assertTrue(res2.capReached());

        var res3 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res3.queued());
        assertFalse(res3.capReached());

        var res4 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res4.queued());
        assertFalse(res4.capReached());

        Integer totalEvents = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId).query(Integer.class).single();
        assertEquals(5, totalEvents);

        Integer totalOutbox = jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE kind = 'REMINDER'")
                .query(Integer.class).single();
        assertEquals(5, totalOutbox);
    }

    @Test
    void testRunCapNoStarvationWhenEarlyCandidatesAlreadyHaveEvents() {
        reminderProperties.setCatchupDays(2);

        // Keyset order is (c.due_date, c.party_id, c.id).
        // By setting due_dates 2026-05-30, 2026-05-30, 2026-05-31, they come strictly before 2026-06-01.
        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p4 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 4", "email": "t4@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        postCharge(workspace1Id, appRentalId, tenant1Id, "Early Charge 1", 10000L, LocalDate.of(2026, 5, 30));
        postCharge(workspace1Id, appRentalId, p2.id(), "Early Charge 2", 20000L, LocalDate.of(2026, 5, 30));
        postCharge(workspace1Id, appRentalId, p3.id(), "Early Charge 3", 30000L, LocalDate.of(2026, 5, 31));

        // Process with cap 10 to write events for all 3 early candidates
        reminderProperties.setMaxRemindersPerAppRun(10);
        var initialRes = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(3, initialRes.queued());

        // Now add candidate 4 with due date 2026-06-01 (later in keyset order)
        postCharge(workspace1Id, appRentalId, p4.id(), "Later Charge 4", 40000L, LocalDate.of(2026, 6, 1));

        // Now set cap = 2.
        reminderProperties.setMaxRemindersPerAppRun(2);
        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        // The 3 early candidates already have reminder_events rows, so they are skippedExisting.
        // Candidate 4 is written without starving.
        assertTrue(res.skippedExisting() >= 3);
        assertEquals(1, res.queued());
        assertFalse(res.capReached());

        Integer totalEvents = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId).query(Integer.class).single();
        assertEquals(4, totalEvents);
    }

    @Test
    void testSkippedEventsCountTowardRunCap() {
        reminderProperties.setCatchupDays(2);
        reminderProperties.setMaxRemindersPerAppRun(2);

        // Candidate 1: party has no email
        PartyResponse pNoEmail = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "No Email", "email": "temp@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        jdbcClient.sql("UPDATE parties SET data = jsonb_set(data, '{email}', '\"\"') WHERE id = :pid")
                .param("pid", pNoEmail.id()).update();

        // Candidate 2: party reminders paused
        PartyResponse pPaused = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Paused", "email": "paused@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        partyStore.pauseReminders(workspace1Id, appRentalId, pPaused.id(), 1, Instant.now());

        // Candidate 3: normal
        PartyResponse pNormal = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Normal", "email": "normal@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        // Use dates to guarantee ordering: 2026-05-30, 2026-05-31, 2026-06-01
        postCharge(workspace1Id, appRentalId, pNoEmail.id(), "Charge No Email", 10000L, LocalDate.of(2026, 5, 30));
        postCharge(workspace1Id, appRentalId, pPaused.id(), "Charge Paused", 20000L, LocalDate.of(2026, 5, 31));
        postCharge(workspace1Id, appRentalId, pNormal.id(), "Charge Normal", 30000L, LocalDate.of(2026, 6, 1));

        // Run 1: writes candidates 1 and 2 (both SKIPPED, total written = 2), reaches cap, stops.
        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res1.queued());
        assertEquals(1, res1.skippedNoEmail());
        assertEquals(1, res1.skippedPaused());
        assertTrue(res1.capReached());

        Integer count1 = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId).query(Integer.class).single();
        assertEquals(2, count1);

        // Candidate 3 is NOT written in run 1
        Integer c3Count = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE party_id = :pid")
                .param("pid", pNormal.id()).query(Integer.class).single();
        assertEquals(0, c3Count);

        // Run 2: candidates 1 and 2 are skipped as existing events, candidate 3 is written (QUEUED).
        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res2.queued());
        assertFalse(res2.capReached());
        assertTrue(res2.skippedExisting() >= 2);

        Integer count2 = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId).query(Integer.class).single();
        assertEquals(3, count2);
    }

    @Test
    void testRunCapLogsOncePerRun(org.springframework.boot.test.system.CapturedOutput output) {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(2);

        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        postCharge(workspace1Id, appRentalId, tenant1Id, "Log Charge 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p2.id(), "Log Charge 2", 20000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p3.id(), "Log Charge 3", 30000L, LocalDate.of(2026, 6, 1));

        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        String logs = output.getAll();
        String expectedMessage = "reached MAX_REMINDERS_PER_APP_RUN (2)";
        assertTrue(logs.contains(expectedMessage), "Logs must contain run cap warning");

        int firstIndex = logs.indexOf(expectedMessage);
        int lastIndex = logs.lastIndexOf(expectedMessage);
        assertEquals(firstIndex, lastIndex, "Run cap warning must be logged exactly once per run");

        assertFalse(logs.contains("Log Charge"), "Logs must not leak charge or candidate details");
    }

    @Test
    void testPreviewReportsCapReached() throws Exception {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(2);

        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        postCharge(workspace1Id, appRentalId, tenant1Id, "Preview Charge 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p2.id(), "Preview Charge 2", 20000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p3.id(), "Preview Charge 3", 30000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, true, false);
        assertTrue(res.capReached());
        assertFalse(res.scanLimitReached());
        assertTrue(res.previewItems().size() <= 2);

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appRentalId + "/reminders/preview")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capReached").value(true))
                .andExpect(jsonPath("$.scanLimitReached").value(false))
                .andExpect(jsonPath("$.items.length()").value(2));

        Integer eventCount = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(0, eventCount);

        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox").query(Integer.class).single();
        assertEquals(0, outboxCount);
    }

    @Test
    void testScanLimitLogsOnce(org.springframework.boot.test.system.CapturedOutput output) {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxCandidatesScannedPerRule(3);
        reminderProperties.setMaxRemindersPerAppRun(100);

        PartyResponse p2 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 2", "email": "t2@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p3 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 3", "email": "t3@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p4 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 4", "email": "t4@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));
        PartyResponse p5 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant 5", "email": "t5@example.com", "monthly_rent": 50000, "due_day": 1, "lease_start": "2026-01-01", "lease_end": "2026-12-31"}
                        """));

        postCharge(workspace1Id, appRentalId, tenant1Id, "Scan Charge 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p2.id(), "Scan Charge 2", 20000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p3.id(), "Scan Charge 3", 30000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p4.id(), "Scan Charge 4", 40000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, p5.id(), "Scan Charge 5", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertTrue(res.scanLimitReached());

        String logs = output.getAll();
        String expectedMessage = "reached scan limit of 3 candidates";
        assertTrue(logs.contains(expectedMessage), "Logs must contain scan limit warning");

        int firstIndex = logs.indexOf(expectedMessage);
        int lastIndex = logs.lastIndexOf(expectedMessage);
        assertEquals(firstIndex, lastIndex, "Scan limit warning must be logged exactly once");
    }

    @Test
    void testOwnerDailyCapRateLimit() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerOwnerPerDay(2);

        // 3 overdue charges that trigger rent_overdue_owner_7 (recipient OWNER)
        postCharge(workspace1Id, appRentalId, tenant1Id, "Overdue Cap 1", 10000L, LocalDate.of(2026, 5, 25));
        postCharge(workspace1Id, appRentalId, tenant1Id, "Overdue Cap 2", 20000L, LocalDate.of(2026, 5, 25));
        postCharge(workspace1Id, appRentalId, tenant1Id, "Overdue Cap 3", 30000L, LocalDate.of(2026, 5, 25));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(4, res.queued());
        assertEquals(2, res.skippedRateLimit());

        List<String> statuses = jdbcClient.sql("SELECT status FROM reminder_events WHERE recipient_kind = 'OWNER' ORDER BY status ASC")
                .query(String.class)
                .list();
        assertEquals(3, statuses.size());
        assertEquals("QUEUED", statuses.get(0));
        assertEquals("QUEUED", statuses.get(1));
        assertEquals("SKIPPED", statuses.get(2));

        String skipReason = jdbcClient.sql("SELECT skip_reason FROM reminder_events WHERE recipient_kind = 'OWNER' AND status = 'SKIPPED'")
                .query(String.class)
                .single();
        assertEquals("RATE_LIMIT", skipReason);
    }

    @Test
    void testPartyDailyCapAcrossTimeZones() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerPartyPerDay(1);

        // 2 charges due today for tenant1
        postCharge(workspace1Id, appRentalId, tenant1Id, "Party Cap Charge 1", 10000L, LocalDate.of(2026, 6, 1));
        postCharge(workspace1Id, appRentalId, tenant1Id, "Party Cap Charge 2", 20000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res.queued());
        assertEquals(1, res.skippedRateLimit());

        // Verify that an event created yesterday in app-local time does not count against today's cap
        UUID oldEventId = UUID.randomUUID();
        UUID oldOutboxId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO email_outbox (id, to_address, subject, body_text, status, kind) VALUES (:id, 'a@b.com', 's', 'b', 'SENT', 'REMINDER')")
                .param("id", oldOutboxId).update();
        jdbcClient.sql("""
                SET session_replication_role = replica;
                INSERT INTO reminder_events (id, workspace_id, app_id, party_id, rule_id, recipient_kind, scheduled_for, status, outbox_id, message_key, created_at)
                VALUES (:id, :wid, :aid, :pid, 'old_rule', 'PARTY', '2026-05-30', 'QUEUED', :oid, 'key', '2026-05-30 10:00:00Z');
                SET session_replication_role = DEFAULT;
                """)
                .param("id", oldEventId)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("pid", tenant1Id)
                .param("oid", oldOutboxId)
                .update();

        long todayCount = reminderEventStore.countQueuedPartyToday(appRentalId, tenant1Id, LocalDate.of(2026, 6, 1), "UTC");
        assertEquals(1, todayCount);
    }

    @Test
    void testOutboxBacklogGuard() {
        reminderProperties.setMaxOutboxBacklog(5);
        for (int i = 0; i < 6; i++) {
            jdbcClient.sql("INSERT INTO email_outbox (id, to_address, subject, body_text, status, kind) VALUES (:id, 'x@test.com', 's', 'b', 'QUEUED', 'VERIFY_EMAIL')")
                    .param("id", UUID.randomUUID())
                    .update();
        }

        postCharge(workspace1Id, appRentalId, tenant1Id, "Backlog Rent Test", 50000L, LocalDate.of(2026, 6, 1));

        // Real run blocked
        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());
        assertTrue(res.blockedByBacklog());

        // Dry run / preview reports blockedByBacklog: true but returns preview items
        var previewRes = reminderEngineService.processApp(workspace1Id, appRentalId, true, false);
        assertTrue(previewRes.blockedByBacklog());
        assertFalse(previewRes.previewItems().isEmpty());
    }

    @Test
    void testOwnerRecipientsCapAndReplyTo() {
        reminderProperties.setCatchupDays(0);

        // Add 5 more verified owners to workspace1 (total 6 owners)
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            UUID extraOwnerId = UUID.randomUUID();
            User u = new User(extraOwnerId, "owner" + i + "@example.com", "hash", now);
            u.setEmailVerifiedAt(now);
            userRepository.save(u);
            membershipRepository.save(new Membership(UUID.randomUUID(), extraOwnerId, workspace1Id, Role.OWNER, now));
        }

        // Post charge triggering rent_overdue_owner_7
        postCharge(workspace1Id, appRentalId, tenant1Id, "Overdue Cap Rent", 50000L, LocalDate.of(2026, 5, 25));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(6, res.queued(), "1 party + 5 owner recipients");

        // OWNER outbox rows must have reply_to NULL
        List<String> replyTos = jdbcClient.sql("SELECT reply_to FROM email_outbox WHERE to_address LIKE 'owner%'")
                .query(String.class)
                .list();
        assertEquals(5, replyTos.size());
        for (String rt : replyTos) {
            assertNull(rt);
        }

        // Now post a party-facing charge
        postCharge(workspace1Id, appRentalId, tenant1Id, "Party Rent Cap", 50000L, LocalDate.of(2026, 6, 1));
        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        // PARTY outbox rows must have earliest verified owner as reply_to
        List<String> partyReplyTos = jdbcClient.sql("SELECT reply_to FROM email_outbox WHERE to_address = 'alice@tenant.com'")
                .query(String.class)
                .list();
        assertFalse(partyReplyTos.isEmpty());
        for (String prt : partyReplyTos) {
            assertEquals("owner@example.com", prt);
        }
    }

    @Test
    void testTemplateExactSubjectsAndSanitization() {
        Map<String, String> vars = Map.of(
                "party", "Alice Tenant",
                "workspace", "Acme Properties",
                "app", "Rental Manager",
                "label", "Rent",
                "amount", "$1,000.00",
                "due", "2026-06-05",
                "days", "3",
                "item", "Tax Document",
                "expiry", "2026-07-01"
        );

        assertEquals("Rent of $1,000.00 is due on 2026-06-05", MessageTemplates.render("charge_reminder_before", vars).subject());
        assertEquals("Rent of $1,000.00 is due today", MessageTemplates.render("charge_reminder_due", vars).subject());
        assertEquals("Rent of $1,000.00 is 3 days overdue", MessageTemplates.render("charge_reminder_late", vars).subject());
        assertEquals("Overdue: Alice Tenant owes $1,000.00", MessageTemplates.render("charge_overdue_owner", vars).subject());
        assertEquals("Please send Tax Document by 2026-06-05", MessageTemplates.render("doc_reminder_before", vars).subject());
        assertEquals("Please send Tax Document today", MessageTemplates.render("doc_reminder_due", vars).subject());
        assertEquals("Tax Document is 3 days overdue", MessageTemplates.render("doc_reminder_late", vars).subject());
        assertEquals("Tax Document expires on 2026-07-01", MessageTemplates.render("doc_expiry_reminder", vars).subject());
        assertEquals("Overdue: Tax Document from Alice Tenant", MessageTemplates.render("doc_overdue_owner", vars).subject());
        assertEquals("Lease ending: Alice Tenant on 2026-07-01", MessageTemplates.render("lease_ending_owner", vars).subject());

        // Sanitization of angle brackets and control characters
        String raw = "Alice <script>alert(1)</script>\r\nSECRET_MARKER_123";
        String sanitized = MessageTemplates.sanitize(raw);
        assertFalse(sanitized.contains("<"));
        assertFalse(sanitized.contains(">"));
        assertFalse(sanitized.contains("\r"));
        assertFalse(sanitized.contains("\n"));
        assertEquals("Alice scriptalert(1)/scriptSECRET_MARKER_123", sanitized);
    }

    @Test
    void testInvalidPartyEmailGivesSkippedNoEmail() {
        reminderProperties.setCatchupDays(0);
        jdbcClient.sql("UPDATE parties SET data = jsonb_set(data, '{email}', '\"invalid-email-address\"') WHERE id = :pid")
                .param("pid", tenant1Id)
                .update();

        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Invalid Email", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res.queued());
        assertEquals(1, res.skippedNoEmail());

        String skipReason = jdbcClient.sql("SELECT skip_reason FROM reminder_events WHERE status = 'SKIPPED' AND party_id = :pid")
                .param("pid", tenant1Id)
                .query(String.class)
                .single();
        assertEquals("NO_EMAIL", skipReason);

        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox").query(Integer.class).single();
        assertEquals(0, outboxCount);
    }

    @Test
    void testEngineNeverCallsEmailSender() {
        com.followwise.email.EmailSender mockSender = org.mockito.Mockito.mock(com.followwise.email.EmailSender.class);
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent No EmailSender", 50000L, LocalDate.of(2026, 6, 1));

        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        org.mockito.Mockito.verifyNoInteractions(mockSender);

        // Verify ReminderEngineService has zero fields of type EmailSender
        boolean hasEmailSenderField = java.util.Arrays.stream(ReminderEngineService.class.getDeclaredFields())
                .anyMatch(f -> com.followwise.email.EmailSender.class.isAssignableFrom(f.getType()));
        assertFalse(hasEmailSenderField, "ReminderEngineService must have no dependency on EmailSender");
    }

    @Test
    void testPreviewDryRunWritesNothing() {
        postCharge(workspace1Id, appRentalId, tenant1Id, "Rent Preview Run", 50000L, LocalDate.of(2026, 6, 1));

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, true, false);
        assertFalse(res.previewItems().isEmpty());
        assertTrue(res.queued() >= 1, "Preview returns what a real run would queue");

        Integer eventCount = jdbcClient.sql("SELECT count(*) FROM reminder_events").query(Integer.class).single();
        assertEquals(0, eventCount);

        Integer outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox").query(Integer.class).single();
        assertEquals(0, outboxCount);
    }

    @Test
    void testLoggingPrivacyAndNoLeak(org.springframework.boot.test.system.CapturedOutput output) {
        String secretMarker = "SECRET_MARKER_123";
        String sensitiveAddress = "123 Secret Street, Apt 4";

        jdbcClient.sql("UPDATE parties SET display_name = 'Alice SECRET_MARKER_123', data = jsonb_set(data, '{name}', '\"Alice SECRET_MARKER_123 123 Secret Street, Apt 4\"') WHERE id = :pid")
                .param("pid", tenant1Id).update();

        postCharge(workspace1Id, appRentalId, tenant1Id, "Secret Rent", 100000L, LocalDate.of(2026, 6, 1));

        reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        String logs = output.getAll();
        assertFalse(logs.contains(secretMarker), "Logs must not leak secret marker from party name");
        assertFalse(logs.contains(sensitiveAddress), "Logs must not leak sensitive address");
    }

    @Test
    void testFailureInOneAppDoesNotStopOtherApps() {
        // App 1 has invalid definition version to force a failure
        AppResponse failingApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Failing App", "UTC"));
        // Corrupt definition of failingApp to invalid structure
        jdbcClient.sql("UPDATE app_definition_versions SET definition = '\"INVALID_JSON_SECRET_MARKER\"'::jsonb WHERE app_id = :aid")
                .param("aid", failingApp.id())
                .update();

        // App 2 is valid with a charge due today
        postCharge(workspace1Id, appRentalId, tenant1Id, "App2 Rent", 50000L, LocalDate.of(2026, 6, 1));

        ReminderScheduler scheduler = new ReminderScheduler(appStore, reminderEngineService, reminderEventStore, reminderProperties);
        // runAll processes all active apps; failing app error is caught, valid app is processed
        scheduler.runAll();

        Integer queuedCount = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid AND status = 'QUEUED'")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertTrue(queuedCount >= 1, "App 2 must be processed successfully despite App 1 failing");
    }

    @Test
    void testKeysetPagingBeyondFirstPage() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(1000);
        reminderProperties.setMaxRemindersPerPartyPerDay(1000);
        reminderProperties.setPageSize(200);

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'Tenant ' || i,
                       jsonb_build_object('name', 'Tenant ' || i, 'email', 'tenant' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 450) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Rent ' || p.display_name, 10000, '2026-06-01', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.id != :t1id
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .param("t1id", tenant1Id)
                .update();

        List<UUID> expectedOrder = jdbcClient.sql("""
                SELECT id FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND party_id != :t1id
                ORDER BY due_date ASC, party_id ASC, id ASC
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("t1id", tenant1Id)
                .query(UUID.class)
                .list();

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(450, res.queued());

        Integer distinctCount = jdbcClient.sql("SELECT count(DISTINCT subject_id) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertEquals(450, distinctCount);

        List<UUID> actualOrder = jdbcClient.sql("""
                SELECT subject_id FROM reminder_events
                WHERE app_id = :aid
                ORDER BY scheduled_for ASC, party_id ASC, subject_id ASC
                """)
                .param("aid", appRentalId)
                .query(UUID.class)
                .list();

        assertEquals(expectedOrder, actualOrder);
    }

    @Test
    void testKeysetCursorSkipsNothingAcrossEqualDates() {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setPageSize(3);
        reminderProperties.setMaxRemindersPerAppRun(100);
        reminderProperties.setMaxRemindersPerPartyPerDay(10);

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'P ' || i,
                       jsonb_build_object('name', 'P ' || i, 'email', 'p' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 8) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Charge ' || p.display_name, 10000, '2026-06-01', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.id != :t1id
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .param("t1id", tenant1Id)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(8, res.queued());

        Integer count = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertEquals(8, count);
    }

    @Test
    void testExistingEventsBeyondFirstPageDoNotStarve() {
        reminderProperties.setCatchupDays(2);
        reminderProperties.setPageSize(3);
        reminderProperties.setMaxRemindersPerPartyPerDay(10);

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'Early ' || i,
                       jsonb_build_object('name', 'Early ' || i, 'email', 'early' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 7) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Early Rent ' || p.display_name, 10000, '2026-05-31', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.id != :t1id
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .param("t1id", tenant1Id)
                .update();

        reminderProperties.setMaxRemindersPerAppRun(10);
        var initialRes = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(7, initialRes.queued());

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'New ' || i,
                       jsonb_build_object('name', 'New ' || i, 'email', 'new' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 4) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'New Rent ' || p.display_name, 10000, '2026-06-01', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.display_name LIKE 'New %'
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        reminderProperties.setPageSize(3);
        reminderProperties.setMaxRemindersPerAppRun(2);
        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);

        assertEquals(2, res.queued());
        assertEquals(7, res.skippedExisting());
        assertTrue(res.capReached());

        Integer totalEvents = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertEquals(9, totalEvents);
    }

    @Test
    void testScanLimitIsPerRuleNotPerRun(org.springframework.boot.test.system.CapturedOutput output) {
        setAppRentalRules("rent_reminder_due", "rent_overdue_owner_7");
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxCandidatesScannedPerRule(3);
        reminderProperties.setMaxRemindersPerAppRun(100);
        reminderProperties.setMaxRemindersPerPartyPerDay(10);
        reminderProperties.setMaxRemindersPerOwnerPerDay(10);

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'ScanA ' || i,
                       jsonb_build_object('name', 'ScanA ' || i, 'email', 'scana' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 5) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Charge A ' || p.display_name, 10000, '2026-06-01', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.display_name LIKE 'ScanA %'
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'ScanB ' || i,
                       jsonb_build_object('name', 'ScanB ' || i, 'email', 'scanb' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 3) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Charge B ' || p.display_name, 10000, '2026-05-25', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.display_name LIKE 'ScanB %'
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(6, res.queued());
        assertTrue(res.scanLimitReached());

        String logs = output.getAll();
        String expectedRuleA = "Rule rent_reminder_due reached scan limit of 3 candidates";
        String expectedRuleB = "Rule rent_overdue_owner_7 reached scan limit of 3 candidates";

        assertTrue(logs.contains(expectedRuleA), "Logs must contain scan limit warning for Rule A");
        assertFalse(logs.contains(expectedRuleB), "Logs must NOT contain scan limit warning for Rule B");

        int firstIndex = logs.indexOf(expectedRuleA);
        int lastIndex = logs.lastIndexOf(expectedRuleA);
        assertEquals(firstIndex, lastIndex, "Scan limit warning for Rule A must be logged exactly once");
    }

    @Test
    void testCapStopsAllRulesInTheRun() {
        setAppRentalRules("rent_reminder_due", "rent_overdue_owner_7");
        reminderProperties.setCatchupDays(0);
        reminderProperties.setMaxRemindersPerAppRun(2);
        reminderProperties.setMaxRemindersPerPartyPerDay(10);
        reminderProperties.setMaxRemindersPerOwnerPerDay(10);

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'CapA ' || i,
                       jsonb_build_object('name', 'CapA ' || i, 'email', 'capa' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 3) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Cap Charge A ' || p.display_name, 10000, '2026-06-01', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.display_name LIKE 'CapA %'
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'tenant', 'CapB ' || i,
                       jsonb_build_object('name', 'CapB ' || i, 'email', 'capb' || i || '@example.com', 'monthly_rent', 10000, 'due_day', 1),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 2) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO ledger_entries (id, workspace_id, app_id, party_id, charge_id, entry_type, kind, label, amount_cents, due_date, created_by)
                SELECT cid, :wid, :aid, p.id, cid, 'CHARGE', 'recurring', 'Cap Charge B ' || p.display_name, 10000, '2026-05-25', :uid
                FROM (
                    SELECT gen_random_uuid() AS cid, p.id, p.display_name
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.display_name LIKE 'CapB %'
                ) p
                """)
                .param("wid", workspace1Id)
                .param("aid", appRentalId)
                .param("uid", ownerId)
                .update();

        var res1 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res1.queued());
        assertTrue(res1.capReached());

        var res2 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(2, res2.queued());
        assertTrue(res2.capReached());

        var res3 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(1, res3.queued());
        assertFalse(res3.capReached());

        var res4 = reminderEngineService.processApp(workspace1Id, appRentalId, false, false);
        assertEquals(0, res4.queued());
        assertFalse(res4.capReached());

        Integer totalEvents = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appRentalId)
                .query(Integer.class)
                .single();
        assertEquals(5, totalEvents);
    }

    @Test
    void testDocumentAndLeaseCandidatesPageInSql() throws Exception {
        reminderProperties.setCatchupDays(0);
        reminderProperties.setPageSize(3);
        reminderProperties.setMaxRemindersPerAppRun(100);
        reminderProperties.setMaxRemindersPerPartyPerDay(10);
        reminderProperties.setMaxRemindersPerOwnerPerDay(10);

        String currentDefJson = jdbcClient.sql("SELECT definition::text FROM app_definition_versions WHERE app_id = :aid AND version = 1")
                .param("aid", appDocId)
                .query(String.class)
                .single();
        tools.jackson.databind.node.ObjectNode defNode = (tools.jackson.databind.node.ObjectNode) jsonMapper.readTree(currentDefJson);
        tools.jackson.databind.node.ArrayNode rules = (tools.jackson.databind.node.ArrayNode) defNode.get("reminderRules");
        tools.jackson.databind.node.ObjectNode leaseRule = jsonMapper.createObjectNode();
        leaseRule.put("id", "lease_end_client_0");
        leaseRule.put("label", "Lease Ending Alert");
        leaseRule.put("partyType", "client");
        leaseRule.put("trigger", "lease.end");
        leaseRule.put("offsetDays", 0);
        leaseRule.put("recipient", "owner");
        leaseRule.put("messageKey", "lease_ending_owner");
        leaseRule.put("channel", "email");
        leaseRule.put("dateField", "lease_end");
        leaseRule.put("enabled", true);
        leaseRule.put("archived", false);
        rules.add(leaseRule);

        jdbcClient.sql("UPDATE app_definition_versions SET definition = :def::jsonb WHERE app_id = :aid AND version = 1")
                .param("def", jsonMapper.writeValueAsString(defNode))
                .param("aid", appDocId)
                .update();

        jdbcClient.sql("""
                INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, created_by, updated_by, created_at, updated_at)
                SELECT gen_random_uuid(), :wid, :aid, 'client', 'DocClient ' || i,
                       jsonb_build_object('name', 'DocClient ' || i, 'email', 'docclient' || i || '@example.com', 'lease_end', '2026-06-01'),
                       1, :uid, :uid, NOW(), NOW()
                FROM generate_series(1, 8) s(i)
                """)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("uid", ownerId)
                .update();

        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, status, due_date, created_by, updated_by)
                SELECT gen_random_uuid(), :wid, :aid, p.id, 'onboarding', 'id_proof', 'ID Proof ' || p.display_name, true, 'PENDING', '2026-06-01', :uid, :uid
                FROM parties p
                WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.id != :c1id
                """)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("uid", ownerId)
                .param("c1id", client1Id)
                .update();

        jdbcClient.sql("""
                INSERT INTO document_requests (id, workspace_id, app_id, party_id, checklist_id, item_id, label, required, status, expires_on, created_by, updated_by)
                SELECT gen_random_uuid(), :wid, :aid, p.id, 'onboarding', 'insurance', 'Insurance ' || p.display_name, true, 'APPROVED', '2026-06-08', :uid, :uid
                FROM parties p
                WHERE p.workspace_id = :wid AND p.app_id = :aid AND p.id != :c1id
                """)
                .param("wid", workspace1Id)
                .param("aid", appDocId)
                .param("uid", ownerId)
                .param("c1id", client1Id)
                .update();

        var res = reminderEngineService.processApp(workspace1Id, appDocId, false, false);
        assertEquals(24, res.queued());

        Integer totalEvents = jdbcClient.sql("SELECT count(*) FROM reminder_events WHERE app_id = :aid")
                .param("aid", appDocId)
                .query(Integer.class)
                .single();
        assertEquals(24, totalEvents);
    }

    private void setAppRentalRules(String... ruleIds) {
        try {
            String currentDefJson = jdbcClient.sql("SELECT definition::text FROM app_definition_versions WHERE app_id = :aid AND version = 1")
                    .param("aid", appRentalId)
                    .query(String.class)
                    .single();
            tools.jackson.databind.node.ObjectNode defNode = (tools.jackson.databind.node.ObjectNode) jsonMapper.readTree(currentDefJson);
            tools.jackson.databind.node.ArrayNode rules = (tools.jackson.databind.node.ArrayNode) defNode.get("reminderRules");
            java.util.Set<String> keep = java.util.Set.of(ruleIds);
            for (int i = rules.size() - 1; i >= 0; i--) {
                String id = rules.get(i).get("id").asText();
                if (!keep.contains(id)) {
                    rules.remove(i);
                }
            }
            jdbcClient.sql("UPDATE app_definition_versions SET definition = :def::jsonb WHERE app_id = :aid AND version = 1")
                    .param("def", jsonMapper.writeValueAsString(defNode))
                    .param("aid", appRentalId)
                    .update();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setUpTenant() {
        Instant now = Instant.now();
        User owner = new User(ownerId, "owner@example.com", "hash", now);
        owner.setEmailVerifiedAt(now);
        userRepository.save(owner);
        Workspace ws1 = new Workspace(UUID.randomUUID(), "Property Co", now);
        workspaceRepository.save(ws1);
        workspace1Id = ws1.getId();
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace1Id, Role.OWNER, now));
        AppResponse rentalApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental App", "UTC"));
        appRentalId = rentalApp.id();
        PartyResponse t1 = partyService.createParty(workspace1Id, appRentalId, ownerId, "tenant",
                jsonMapper.readTree("""
                        {
                          "name": "Alice Tenant",
                          "email": "alice@tenant.com",
                          "monthly_rent": 100000,
                          "due_day": 1,
                          "lease_start": "2026-01-01",
                          "lease_end": "2026-12-31"
                        }
                        """));
        tenant1Id = t1.id();
    }
}
