package com.followwise.ledger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppRecord;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.app.AppStore;
import com.followwise.identity.AuthService;
import com.followwise.identity.LoginRequest;
import com.followwise.identity.SystemUser;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyService;
import com.followwise.party.PartyStore;
import com.followwise.party.RecordService;
import com.followwise.security.TokenService;
import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(ChargeGenerationTest.TestClockConfig.class)
class ChargeGenerationTest extends AbstractIntegrationTest {

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
    private AppStore appStore;

    @Autowired
    private PartyService partyService;

    @Autowired
    private PartyStore partyStore;

    @Autowired
    private RecordService recordService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerStore ledgerStore;

    @Autowired
    private ChargeGeneratorService chargeGeneratorService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspace1Id;
    private UUID workspace2Id;
    private UUID app1Id;
    private UUID app2Id;
    private UUID ownerId;
    private UUID staffId;
    private UUID portalId;
    private UUID nonMemberId;
    private String ownerToken;
    private String staffToken;
    private String portalToken;
    private String nonMemberToken;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TestDbHelper.cleanDatabase(dataSource);
    }

    @BeforeEach
    void setUp() {
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T12:00:00Z"));
        TestDbHelper.cleanDatabase(dataSource);

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
                new com.followwise.app.AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", "America/New_York"));
        app1Id = app1.id();

        AppResponse app2 = appService.createApp(workspace2Id, nonMemberId,
                new com.followwise.app.AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", "America/New_York"));
        app2Id = app2.id();
    }

    private UUID createTenant(UUID workspaceId, UUID appId, String name, String email, int dueDay, long monthlyRent, Instant createdAt) {
        UUID id = UUID.randomUUID();
        String json = String.format("{\"name\":\"%s\",\"email\":\"%s\",\"due_day\":%d,\"monthly_rent\":%d,\"lease_start\":\"2026-01-01\",\"lease_end\":\"2026-12-31\"}",
                name, email, dueDay, monthlyRent);
        partyStore.insert(id, workspaceId, appId, "tenant", name, json, 1, ownerId, createdAt);
        return id;
    }

    @Test
    void rpmTenant_dueDayAndRentFromField_createsCorrectCharge() {
        // Today is June 1, 2026. Catchup 35 days (April 27), Lead 14 days (June 15).
        // Tenant created on 2026-05-01. Due day 5 -> May 5 and June 5 are within window and >= creation.
        UUID tenantId = createTenant(workspace1Id, app1Id, "Alice Tenant", "alice@example.com", 5, 120000L, Instant.parse("2026-05-01T10:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(2, res.created());
        assertEquals(0, res.skippedExisting());
        assertEquals(0, res.skippedInvalid());
        assertEquals(0, res.skippedLocked());
        assertEquals(0, res.failed());

        // Check entries in DB
        List<LedgerEntry> charges = jdbcClient.sql("SELECT * FROM ledger_entries WHERE app_id = :aid AND party_id = :pid ORDER BY due_date ASC")
                .param("aid", app1Id)
                .param("pid", tenantId)
                .query(LedgerStore.class.getDeclaredClasses()[0].equals(LedgerEntry.class) ? null : (rs, rowNum) -> new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("app_id", UUID.class),
                        rs.getObject("party_id", UUID.class),
                        rs.getObject("charge_id", UUID.class),
                        rs.getString("entry_type"),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("kind"),
                        rs.getString("schedule_id"),
                        rs.getString("period_key"),
                        rs.getString("label"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getObject("paid_on", LocalDate.class),
                        rs.getString("note"),
                        rs.getObject("reverses_entry_id", UUID.class),
                        rs.getObject("idempotency_key", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();

        assertEquals(2, charges.size());
        LedgerEntry mayCharge = charges.get(0);
        assertEquals(LocalDate.of(2026, 5, 5), mayCharge.dueDate());
        assertEquals(120000L, mayCharge.amountCents());
        assertEquals("USD", mayCharge.currency());
        assertEquals("recurring", mayCharge.kind());
        assertEquals("monthly_rent", mayCharge.scheduleId());
        assertEquals("2026-05", mayCharge.periodKey());
        assertEquals("Monthly Rent 2026-05", mayCharge.label());
        assertEquals(SystemUser.ID, mayCharge.createdBy());

        LedgerEntry junCharge = charges.get(1);
        assertEquals(LocalDate.of(2026, 6, 5), junCharge.dueDate());
        assertEquals("2026-06", junCharge.periodKey());
    }

    @Test
    void fixedAmountAndFixedDueDay() throws Exception {
        // Update app definition to have a recurring schedule with fixed amount and fixed dueDay
        String newDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [
                        { "id": "name", "label": "Full Name", "type": "text", "required": true }
                    ]}
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "flat_fee",
                      "label": "Flat Amenity Fee",
                      "kind": "recurring",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 2500,
                      "dueDaySource": "fixed",
                      "dueDay": 10,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, newDef, "Fixed schedule", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Bob", "{\"name\":\"Bob\"}", 2, ownerId, Instant.parse("2026-05-01T10:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertTrue(res.created() >= 1);

        long count = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE app_id = :aid AND party_id = :pid AND amount_cents = 2500 AND due_date = '2026-06-10'")
                .param("aid", app1Id)
                .param("pid", tenantId)
                .query(Long.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void windowBoundaries_exactly35BeforeAnd14AfterIn_outsideAreOut() {
        // Today is 2026-06-01. Window: [2026-04-27, 2026-06-15].
        Instant old = Instant.parse("2025-01-01T00:00:00Z");
        // Party A with due day 27 in April -> April 27 (exactly today - 35) -> IN
        createTenant(workspace1Id, app1Id, "InApril", "in1@test.com", 27, 10000L, old);
        // Party B with due day 26 in April -> April 26 (36 days before) -> OUT
        createTenant(workspace1Id, app1Id, "OutApril", "out1@test.com", 26, 10000L, old);
        // Party C with due day 15 in June -> June 15 (exactly today + 14) -> IN
        createTenant(workspace1Id, app1Id, "InJune", "in2@test.com", 15, 10000L, old);
        // Party D with due day 16 in June -> June 16 (15 days after) -> OUT
        createTenant(workspace1Id, app1Id, "OutJune", "out2@test.com", 16, 10000L, old);

        chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);

        // Verify InApril got 2026-04-27
        assertTrue(jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE due_date = '2026-04-27')").query(Boolean.class).single());
        // Verify OutApril did NOT get 2026-04-26
        assertFalse(jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE due_date = '2026-04-26')").query(Boolean.class).single());
        // Verify InJune got 2026-06-15
        assertTrue(jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE due_date = '2026-06-15')").query(Boolean.class).single());
        // Verify OutJune did NOT get 2026-06-16
        assertFalse(jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE due_date = '2026-06-16')").query(Boolean.class).single());
    }

    @Test
    void partyCreatedAfterDueDay_getsFirstChargeNextMonth() {
        // Today is June 1, 2026.
        // Party created on June 10, 2026 with dueDay 5.
        UUID pid = createTenant(workspace1Id, app1Id, "LateTenant", "late@test.com", 5, 50000L, Instant.parse("2026-06-10T12:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        // May 5 and June 5 are before creation date (June 10), so 0 charges generated
        assertEquals(0, res.created());

        // Now move clock to July 1, 2026
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-07-01T12:00:00Z"));
        ChargeGenerationResult resJuly = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, resJuly.created());

        long count = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE party_id = :pid AND period_key = '2026-07'")
                .param("pid", pid)
                .query(Long.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void partyCreatedOnDueDayItself_getsThatMonthCharge() {
        // Today is June 1, 2026. App timezone America/New_York.
        // Party created on 2026-06-05T10:00:00-04:00 (due day 5).
        UUID pid = createTenant(workspace1Id, app1Id, "SameDayTenant", "sameday@test.com", 5, 50000L, Instant.parse("2026-06-05T14:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, res.created());

        long count = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE party_id = :pid AND period_key = '2026-06'")
                .param("pid", pid)
                .query(Long.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void idempotentRerun_skippedExisting_andConcurrentRuns() throws Exception {
        createTenant(workspace1Id, app1Id, "IdemTenant", "idem@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));

        ChargeGenerationResult run1 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, run1.created());
        assertEquals(0, run1.skippedExisting());

        // Re-run
        ChargeGenerationResult run2 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, run2.created());
        assertEquals(1, run2.skippedExisting());

        // Concurrent runs
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger totalCreated = new AtomicInteger();
        AtomicInteger totalSkipped = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    ChargeGenerationResult r = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
                    totalCreated.addAndGet(r.created());
                    totalSkipped.addAndGet(r.skippedExisting());
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, totalCreated.get());
        assertEquals(threads, totalSkipped.get());
    }

    @Test
    void clockJumpsThreeMonths_createsOnlyInWindowMonths() {
        UUID pid = createTenant(workspace1Id, app1Id, "JumpTenant", "jump@test.com", 1, 50000L, Instant.parse("2026-01-01T00:00:00Z"));

        // Jump clock to September 1, 2026. Window [2026-07-28, 2026-09-15].
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-09-01T12:00:00Z"));
        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);

        // Only August (2026-08-01 is within 35 days) and September (2026-09-01) qualify.
        // Jan..July are outside window!
        assertEquals(2, res.created());
        List<String> periodKeys = jdbcClient.sql("SELECT period_key FROM ledger_entries WHERE party_id = :pid ORDER BY due_date ASC")
                .param("pid", pid)
                .query(String.class)
                .list();
        assertEquals(List.of("2026-08", "2026-09"), periodKeys);
    }

    @Test
    void monthEndsAndLeapDayClock() {
        // Test leap day Feb 29, 2024
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2024-02-29T12:00:00Z"));
        createTenant(workspace1Id, app1Id, "LeapTenant", "leap@test.com", 28, 50000L, Instant.parse("2024-01-01T00:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertTrue(res.created() >= 1);
        long feb28 = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE due_date = '2024-02-28'").query(Long.class).single();
        assertEquals(1, feb28);
    }

    @Test
    void invalidOrMissingFieldValues_countedAsSkippedInvalid() {
        // Party with due_day = 0
        createTenant(workspace1Id, app1Id, "BadDueDay0", "b0@test.com", 0, 50000L, Instant.now());
        // Party with due_day = 29
        createTenant(workspace1Id, app1Id, "BadDueDay29", "b29@test.com", 29, 50000L, Instant.now());
        // Party with monthly_rent = 0
        createTenant(workspace1Id, app1Id, "BadRent0", "br0@test.com", 5, 0L, Instant.now());
        // Party with monthly_rent > 100,000,000,000
        createTenant(workspace1Id, app1Id, "BadRentBig", "brb@test.com", 5, 100_000_000_001L, Instant.now());
        // Party with non-integral due_day (string)
        UUID pString = UUID.randomUUID();
        partyStore.insert(pString, workspace1Id, app1Id, "tenant", "Str", "{\"name\":\"Str\",\"due_day\":\"five\",\"monthly_rent\":5000}", 1, ownerId, Instant.now());
        // Party with floating point due_day (decimal)
        UUID pDec = UUID.randomUUID();
        partyStore.insert(pDec, workspace1Id, app1Id, "tenant", "Dec", "{\"name\":\"Dec\",\"due_day\":5.5,\"monthly_rent\":5000}", 1, ownerId, Instant.now());

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(6, res.skippedInvalid());
        assertEquals(0, res.created());
    }

    @Test
    void archivedParty_disabledSchedule_archivedSchedule_skipped() throws Exception {
        // Archived party
        UUID pArchived = createTenant(workspace1Id, app1Id, "ArchivedTenant", "arch@test.com", 5, 50000L, Instant.parse("2026-05-01T00:00:00Z"));
        partyService.archiveParty(workspace1Id, app1Id, pArchived, ownerId);

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res.created());
        assertEquals(0, res.skippedInvalid());

        // Disabled schedule
        String disabledScheduleDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "disabled_sched",
                      "label": "Disabled",
                      "kind": "recurring",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 1000,
                      "dueDaySource": "fixed",
                      "dueDay": 5,
                      "enabled": false,
                      "archived": false
                    },
                    {
                      "id": "archived_sched",
                      "label": "Archived",
                      "kind": "recurring",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 1000,
                      "dueDaySource": "fixed",
                      "dueDay": 5,
                      "enabled": true,
                      "archived": true
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, disabledScheduleDef, "Disabled & archived schedules", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID activeTenant = UUID.randomUUID();
        partyStore.insert(activeTenant, workspace1Id, app1Id, "tenant", "Active", "{}", 2, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        ChargeGenerationResult res2 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res2.created());
    }

    @Test
    void schedulePartyTypeFilter() throws Exception {
        // App definition with a schedule for partyType "tenant"
        // Insert a party with partyType "vendor"
        UUID vendorId = UUID.randomUUID();
        partyStore.insert(vendorId, workspace1Id, app1Id, "vendor", "Vendor Corp", "{\"due_day\":5,\"monthly_rent\":50000}", 1, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res.created());
    }

    @Test
    void periodKeyAndLabelFormat_120CharTruncation() throws Exception {
        String longLabel = "A".repeat(150);
        String def = String.format("""
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "long_sched",
                      "label": "%s",
                      "kind": "recurring",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 1000,
                      "dueDaySource": "fixed",
                      "dueDay": 5,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """, longLabel.substring(0, 115));
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, def, "Long label", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Bob", "{}", 2, ownerId, Instant.parse("2026-06-01T00:00:00Z"));

        chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);

        String label = jdbcClient.sql("SELECT label FROM ledger_entries WHERE app_id = :aid AND party_id = :pid")
                .param("aid", app1Id)
                .param("pid", tenantId)
                .query(String.class)
                .single();
        assertTrue(label.length() <= 120);
        assertTrue(label.startsWith("A".repeat(100)));
    }

    @Test
    void appTimezonesAcrossDateLine_produceDifferentToday() throws Exception {
        // Instant: 2026-06-01T00:30:00Z
        // In Pacific/Auckland (+12), it is 2026-06-01 12:30. Today is 2026-06-01.
        // In Pacific/Honolulu (-10), it is 2026-05-31 14:30. Today is 2026-05-31.
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T00:30:00Z"));

        String aucklandDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "Pacific/Auckland",
                  "partyTypes": [],
                  "recordTypes": [],
                  "chargeSchedules": [],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, aucklandDef, "Auckland", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        String honoluluDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "Pacific/Honolulu",
                  "partyTypes": [],
                  "recordTypes": [],
                  "chargeSchedules": [],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app2Id, 2, honoluluDef, "Honolulu", nonMemberId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app2Id, workspace2Id, 1, 2, "Rental Property Manager", Instant.now());

        ChargeGenerationResult res1 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, true, false);
        ChargeGenerationResult res2 = chargeGeneratorService.generateForApp(workspace2Id, app2Id, nonMemberId, true, false);

        assertEquals(LocalDate.of(2026, 6, 1), res1.today());
        assertEquals(LocalDate.of(2026, 5, 31), res2.today());
    }

    @Test
    void lateFeeAfterGrace_boundaryCheck_andOneFeePerCharge() throws Exception {
        // Enable late fee schedule with graceDays = 5, amount = 5000 fixed
        String lateDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "late_fee",
                      "label": "Late Fee",
                      "kind": "late_fee",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 5000,
                      "graceDays": 5,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, lateDef, "Enable late fee", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Tenant", "{}", 2, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        // Insert a recurring charge with due date = 2026-06-01
        UUID chargeId = UUID.randomUUID();
        ledgerStore.insertEntry(
                chargeId, workspace1Id, app1Id, tenantId, chargeId,
                "CHARGE", 100000L, "USD", "recurring",
                "monthly_rent", "2026-06", "Rent June", LocalDate.of(2026, 6, 1),
                null, null, null, null, null, SystemUser.ID
        );

        // Due date = 2026-06-01. Grace days = 5.
        // Due + graceDays = 2026-06-06.
        // On 2026-06-06: 2026-06-06 is NOT < 2026-06-06 -> not yet late!
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-06T12:00:00Z"));
        ChargeGenerationResult resBoundary = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, resBoundary.created());

        // On 2026-06-07: 2026-06-06 < 2026-06-07 -> late fee is CREATED!
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-07T12:00:00Z"));
        ChargeGenerationResult resLate = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, resLate.created());

        // Verify late fee entry
        LedgerEntry lateFeeEntry = jdbcClient.sql("SELECT * FROM ledger_entries WHERE app_id = :aid AND kind = 'late_fee'")
                .param("aid", app1Id)
                .query((rs, rowNum) -> new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("app_id", UUID.class),
                        rs.getObject("party_id", UUID.class),
                        rs.getObject("charge_id", UUID.class),
                        rs.getString("entry_type"),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("kind"),
                        rs.getString("schedule_id"),
                        rs.getString("period_key"),
                        rs.getString("label"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getObject("paid_on", LocalDate.class),
                        rs.getString("note"),
                        rs.getObject("reverses_entry_id", UUID.class),
                        rs.getObject("idempotency_key", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .single();
        assertEquals(5000L, lateFeeEntry.amountCents());
        assertEquals("late_fee", lateFeeEntry.kind());
        assertEquals("late_fee", lateFeeEntry.scheduleId());
        assertEquals(chargeId.toString(), lateFeeEntry.periodKey());
        assertEquals("Late fee Rent June", lateFeeEntry.label());
        assertEquals(LocalDate.of(2026, 6, 7), lateFeeEntry.dueDate());

        // Re-run on 2026-06-08: no duplicate fee!
        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-08T12:00:00Z"));
        ChargeGenerationResult resNextDay = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, resNextDay.created());
        assertEquals(1, resNextDay.skippedExisting());
    }

    @Test
    void lateFee_paidChargeOrReversedCharge_noLateFee() throws Exception {
        String lateDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "late_fee",
                      "label": "Late Fee",
                      "kind": "late_fee",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 5000,
                      "graceDays": 0,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, lateDef, "Late fee", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Tenant", "{}", 2, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        // Charge 1: fully paid
        UUID c1 = UUID.randomUUID();
        ledgerStore.insertEntry(c1, workspace1Id, app1Id, tenantId, c1, "CHARGE", 10000L, "USD", "recurring", "monthly_rent", "2026-03", "Rent 1", LocalDate.of(2026, 3, 1), null, null, null, null, null, SystemUser.ID);
        ledgerStore.insertEntry(UUID.randomUUID(), workspace1Id, app1Id, tenantId, c1, "PAYMENT", -10000L, "USD", null, null, null, null, null, LocalDate.of(2026, 3, 2), null, null, null, null, ownerId);

        // Charge 2: reversed
        UUID c2 = UUID.randomUUID();
        ledgerStore.insertEntry(c2, workspace1Id, app1Id, tenantId, c2, "CHARGE", 10000L, "USD", "recurring", "monthly_rent", "2026-04", "Rent 2", LocalDate.of(2026, 4, 1), null, null, null, null, null, SystemUser.ID);
        ledgerStore.insertEntry(UUID.randomUUID(), workspace1Id, app1Id, tenantId, c2, "REVERSAL", -10000L, "USD", null, null, null, null, null, null, "mistake", c2, null, null, ownerId);

        // Charge 3: partly paid (10000 charged, 4000 paid -> 6000 outstanding)
        UUID c3 = UUID.randomUUID();
        ledgerStore.insertEntry(c3, workspace1Id, app1Id, tenantId, c3, "CHARGE", 10000L, "USD", "recurring", "monthly_rent", "2026-05", "Rent 3", LocalDate.of(2026, 5, 1), null, null, null, null, null, SystemUser.ID);
        ledgerStore.insertEntry(UUID.randomUUID(), workspace1Id, app1Id, tenantId, c3, "PAYMENT", -4000L, "USD", null, null, null, null, null, LocalDate.of(2026, 5, 2), null, null, null, null, ownerId);

        // Manual charge: overdue and unpaid
        UUID c4 = UUID.randomUUID();
        ledgerStore.insertEntry(c4, workspace1Id, app1Id, tenantId, c4, "CHARGE", 15000L, "USD", "manual", null, null, "Manual repairs", LocalDate.of(2026, 5, 1), null, null, null, null, null, ownerId);

        TestClockConfig.MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T12:00:00Z"));
        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);

        // c1 (paid) -> no fee
        // c2 (reversed) -> no fee
        // c3 (partly paid) -> fee!
        // c4 (manual) -> fee!
        assertEquals(2, res.created());
    }

    @Test
    void reversedLateFee_isNotRegenerated() throws Exception {
        String lateDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "late_fee",
                      "label": "Late Fee",
                      "kind": "late_fee",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 5000,
                      "graceDays": 0,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, lateDef, "Late fee", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Tenant", "{}", 2, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        UUID c1 = UUID.randomUUID();
        ledgerStore.insertEntry(c1, workspace1Id, app1Id, tenantId, c1, "CHARGE", 10000L, "USD", "recurring", "monthly_rent", "2026-05", "Rent", LocalDate.of(2026, 5, 1), null, null, null, null, null, SystemUser.ID);

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, res.created());

        // Find the late fee charge id
        UUID lateFeeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE app_id = :aid AND kind = 'late_fee'").param("aid", app1Id).query(UUID.class).single();

        // Reverse the late fee
        ledgerStore.insertEntry(UUID.randomUUID(), workspace1Id, app1Id, tenantId, lateFeeId, "REVERSAL", -5000L, "USD", null, null, null, null, null, null, "Forgiven", lateFeeId, null, null, ownerId);

        // Next run should NOT recreate the late fee
        ChargeGenerationResult res2 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res2.created());
        assertEquals(1, res2.skippedExisting());
    }

    @Test
    void lateFee_archivedParty_stopsAccrual() throws Exception {
        String lateDef = """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Rental Property Manager",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    { "id": "tenant", "label": "Tenant", "archived": false, "fields": [] }
                  ],
                  "recordTypes": [],
                  "chargeSchedules": [
                    {
                      "id": "late_fee",
                      "label": "Late Fee",
                      "kind": "late_fee",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": 5000,
                      "graceDays": 0,
                      "enabled": true,
                      "archived": false
                    }
                  ],
                  "reminderRules": [],
                  "widgets": []
                }
                """;
        appStore.insertVersion(UUID.randomUUID(), app1Id, 2, lateDef, "Late fee", ownerId, Instant.now());
        appStore.updateAppDefinitionAndVersion(app1Id, workspace1Id, 1, 2, "Rental Property Manager", Instant.now());

        UUID tenantId = UUID.randomUUID();
        partyStore.insert(tenantId, workspace1Id, app1Id, "tenant", "Tenant", "{}", 2, ownerId, Instant.parse("2026-05-01T00:00:00Z"));

        UUID c1 = UUID.randomUUID();
        ledgerStore.insertEntry(c1, workspace1Id, app1Id, tenantId, c1, "CHARGE", 10000L, "USD", "recurring", "monthly_rent", "2026-05", "Rent", LocalDate.of(2026, 5, 1), null, null, null, null, null, SystemUser.ID);

        // Archive party before generation
        partyService.archiveParty(workspace1Id, app1Id, tenantId, ownerId);

        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res.created());
    }

    @Test
    void systemUserGuards() throws Exception {
        // 1. Login as system user -> generic 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"system@followwise.invalid\",\"password\":\"somePassword123!\"}"))
                .andExpect(status().isUnauthorized());

        // 2. Forgot password for system user -> 204, but no tokens issued and no outbox rows
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"system@followwise.invalid\"}"))
                .andExpect(status().isNoContent());

        long tokenCount = jdbcClient.sql("SELECT count(*) FROM auth_tokens WHERE user_id = '00000000-0000-0000-0000-000000000001'").query(Long.class).single();
        assertEquals(0, tokenCount);

        long outboxCount = jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE to_address = 'system@followwise.invalid'").query(Long.class).single();
        assertEquals(0, outboxCount);

        // 3. System user has no membership
        long memberCount = jdbcClient.sql("SELECT count(*) FROM memberships WHERE user_id = '00000000-0000-0000-0000-000000000001'").query(Long.class).single();
        assertEquals(0, memberCount);

        // 4. Direct SQL check on locked_until
        String lockedUntil = jdbcClient.sql("SELECT locked_until::text FROM users WHERE email = 'system@followwise.invalid'").query(String.class).single();
        assertEquals("infinity", lockedUntil);

        // 5. Resend verification fails for system user
        assertThrows(IllegalArgumentException.class, () -> authService.resendVerification(SystemUser.ID));
    }

    @Test
    void dryRun_writesNothing_andMatchesRealRun() {
        createTenant(workspace1Id, app1Id, "DryRunTenant", "dry@test.com", 5, 75000L, Instant.parse("2026-06-01T00:00:00Z"));

        // Dry run
        ChargeGenerationResult dry = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, true, false);
        assertTrue(dry.dryRun());
        assertEquals(1, dry.created());
        assertEquals(0, dry.skippedExisting());

        // Assert 0 rows in ledger_entries
        long countAfterDry = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE app_id = :aid").param("aid", app1Id).query(Long.class).single();
        assertEquals(0, countAfterDry);

        // Real run
        ChargeGenerationResult real = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertFalse(real.dryRun());
        assertEquals(1, real.created());
        assertEquals(0, real.skippedExisting());

        long countAfterReal = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE app_id = :aid").param("aid", app1Id).query(Long.class).single();
        assertEquals(1, countAfterReal);
    }

    @Test
    void endpointPermissionMatrix_andValidation() throws Exception {
        createTenant(workspace1Id, app1Id, "PermTenant", "perm@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));

        // OWNER -> 200
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate?dryRun=true", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun", is(true)))
                .andExpect(jsonPath("$.created", is(1)));

        // STAFF -> 403
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        // PORTAL_USER -> 403
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Non-member -> 404
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // Invalid dryRun parameter -> 400
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate?dryRun=foo", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());

        // Non-existent app -> 404
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // Archived app -> 409
        appService.archiveApp(workspace1Id, app1Id, ownerId);
        mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, app1Id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void schedulerSkipsLockedApp_whileEndpointWaits() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        // Start transaction holding app lock
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.submit(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.execute(status -> {
                appStore.lockAppRow(workspace1Id, app1Id, false);
                lockAcquired.countDown();
                try {
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return null;
            });
        });

        assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

        // Scheduler call with skipLocked=true should return skippedLocked=1 immediately
        ChargeGenerationResult res = chargeGeneratorService.generateForApp(workspace1Id, app1Id, SystemUser.ID, false, true);
        assertEquals(1, res.skippedLocked());
        assertEquals(0, res.created());

        // Release lock
        releaseLock.countDown();
        ex.shutdown();
        ex.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    void capturedLogs_containNoSecretMarkerNorEmail() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChargeGeneratorService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            createTenant(workspace1Id, app1Id, "Secret SECRET_MARKER_123 Person", "sensitive_marker@example.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));
            chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);

            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                assertFalse(msg.contains("SECRET_MARKER_123"), "Log contains SECRET_MARKER_123: " + msg);
                assertFalse(msg.contains("@example.com"), "Log contains email: " + msg);
            }
        } finally {
            logger.detachAppender(listAppender);
        }
    }

    @Test
    void reversedRecurringCharge_notRegenerated() {
        UUID tenantId = createTenant(workspace1Id, app1Id, "RevTenant", "rev@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));

        ChargeGenerationResult res1 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(1, res1.created());

        UUID chargeId = jdbcClient.sql("SELECT id FROM ledger_entries WHERE app_id = :aid AND party_id = :pid AND entry_type = 'CHARGE'")
                .param("aid", app1Id)
                .param("pid", tenantId)
                .query(UUID.class)
                .single();

        ledgerStore.insertEntry(
                UUID.randomUUID(), workspace1Id, app1Id, tenantId, chargeId,
                "REVERSAL", -50000L, "USD", null, null, null, null, null, null,
                "Mistake", chargeId, null, null, ownerId
        );

        ChargeGenerationResult res2 = chargeGeneratorService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, res2.created());
        assertEquals(1, res2.skippedExisting());
    }

    @Test
    void perRunCap_andLedgerLimit_stopsGeneration() {
        ChargeGeneratorService customService = new ChargeGeneratorService(
                ledgerStore, appStore, partyStore, TestClockConfig.MUTABLE_CLOCK,
                35, 14, 60, 2, 20000
        );
        createTenant(workspace1Id, app1Id, "Cap1", "cap1@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));
        createTenant(workspace1Id, app1Id, "Cap2", "cap2@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));
        createTenant(workspace1Id, app1Id, "Cap3", "cap3@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));

        ChargeGenerationResult res = customService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(2, res.created());

        ChargeGeneratorService limitService = new ChargeGeneratorService(
                ledgerStore, appStore, partyStore, TestClockConfig.MUTABLE_CLOCK,
                35, 14, 60, 500, 2
        );
        ChargeGenerationResult resLimit = limitService.generateForApp(workspace1Id, app1Id, ownerId, false, false);
        assertEquals(0, resLimit.created());
    }

    @Test
    void endpointWaitsAndSerializes_noDuplicates() throws Exception {
        createTenant(workspace1Id, app1Id, "ConcurrentTenant", "conc@test.com", 5, 50000L, Instant.parse("2026-06-01T00:00:00Z"));

        int threads = 3;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            ex.submit(() -> {
                try {
                    mockMvc.perform(post("/api/workspaces/{wid}/apps/{aid}/charges/generate", workspace1Id, app1Id)
                                    .header("Authorization", "Bearer " + ownerToken))
                            .andExpect(status().isOk());
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        ex.shutdown();

        assertEquals(threads, successCount.get());
        long totalCharges = jdbcClient.sql("SELECT count(*) FROM ledger_entries WHERE app_id = :aid AND entry_type = 'CHARGE'")
                .param("aid", app1Id)
                .query(Long.class)
                .single();
        assertEquals(1, totalCharges);
    }

    @Test
    void startupValidationFailures() {
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, 14, 4, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, 14, 1441, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), -1, 14, 60, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 91, 14, 60, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, -1, 60, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, 61, 60, 500, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, 14, 60, 0, 20000).validateProperties());
        assertThrows(IllegalStateException.class, () ->
                new ChargeGeneratorService(ledgerStore, appStore, partyStore, Clock.systemUTC(), 35, 14, 60, 2001, 20000).validateProperties());
    }
}
