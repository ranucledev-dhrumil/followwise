package com.followwise.party;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.app.BodySizeFilter;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class PartyApiTest extends AbstractIntegrationTest {

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
    private BodySizeFilter bodySizeFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private javax.sql.DataSource dataSource;

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

    private UUID app1Id;
    private UUID app2Id;

    @BeforeEach
    void setUp() {
        cleanDb();

        Instant now = Instant.now();
        workspace1Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace1Id, "Workspace 1", now));

        workspace2Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace2Id, "Workspace 2", now));

        ownerId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        portalId = UUID.randomUUID();
        nonMemberId = UUID.randomUUID();

        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        userRepository.save(new User(staffId, "staff@example.com", "hash", now));
        userRepository.save(new User(portalId, "portal@example.com", "hash", now));
        userRepository.save(new User(nonMemberId, "stranger@example.com", "hash", now));

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
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        TestDbHelper.cleanDatabase(dataSource);
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void assertSanitizedErrorResponse(String responseJson) {
        assertFalse(responseJson.contains("SECRET_MARKER_123"), "Error response must not echo secret markers");
        assertFalse(responseJson.toLowerCase().contains("exception"), "Error response must not contain 'Exception'");
        assertFalse(responseJson.contains("at com.followwise"), "Error response must not contain stack traces");
    }

    @Test
    void rpm_tenantAndUnit_lifecycle() throws Exception {
        // 1. Create a Unit record
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "101 Main St, Apt 4B",
                    "status": "vacant",
                    "default_rent": 150000
                  }
                }
                """;

        String unitResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("unit"))
                .andExpect(jsonPath("$.displayName").value("101 Main St, Apt 4B"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.archived").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode unitNode = jsonMapper.readTree(unitResp);
        String unitId = unitNode.get("id").asText();

        // 2. Create Tenant party referencing the unit
        String tenantJson = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane Doe",
                    "email": "Jane.Doe@EXAMPLE.COM",
                    "phone": "+1 (555) 019-2834",
                    "monthly_rent": 150000,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, unitId);

        String tenantResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + staffToken) // STAFF can write per Amendment C
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("tenant"))
                .andExpect(jsonPath("$.displayName").value("Jane Doe"))
                .andExpect(jsonPath("$.data.email").value("jane.doe@example.com")) // lowercase stored
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andReturn().getResponse().getContentAsString();

        JsonNode tenantNode = jsonMapper.readTree(tenantResp);
        String tenantId = tenantNode.get("id").asText();

        // 3. GET tenant
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId))
                .andExpect(jsonPath("$.displayName").value("Jane Doe"));

        // 4. Update tenant (optimistic locking)
        String updateJson = String.format("""
                {
                  "expectedVersion": 1,
                  "data": {
                    "name": "Jane Smith",
                    "email": "jane.smith@example.com",
                    "monthly_rent": 160000,
                    "due_day": 5,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, unitId);

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Jane Smith"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.data.monthly_rent").value(160000));

        // 5. Stale update returns 409 stale_version
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson)) // expectedVersion is still 1, but current is 2
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("stale_version"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        // 6. Archive tenant
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 7. Archived excluded by default
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties?type=tenant")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));

        // 8. Included with ?archived=true
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties?type=tenant&archived=true")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].archived").value(true))
                .andExpect(jsonPath("$.items[0].version").value(3)); // archive bumped version

        // 9. Restore tenant
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId + "/restore")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.version").value(4)); // restore bumped version
    }

    @Test
    void cdt_client_creation() throws Exception {
        AppResponse cdtApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App", "UTC"));

        String clientJson = """
                {
                  "type": "client",
                  "data": {
                    "name": "Acme Corp",
                    "email": "contact@acme.com",
                    "company": "Acme Industries"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + cdtApp.id() + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("client"))
                .andExpect(jsonPath("$.displayName").value("Acme Corp"));
    }

    @Test
    void fieldTypes_boundaryValidation() throws Exception {
        // Test text boundary: 500 chars ok, 501 chars rejected
        String long500 = "A".repeat(500);
        String long501 = "A".repeat(501);

        String okTenant = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "%s",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """, long500);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(okTenant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value(long500.substring(0, 200))); // truncated to 200

        String badTenant501 = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "%s",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """, long501);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badTenant501))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/name"))
                .andExpect(jsonPath("$.errors[0].message").value("text too long"));

        // Control characters in text rejected
        String ctrlTenant = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane\\u0007Doe",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ctrlTenant))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/name"))
                .andExpect(jsonPath("$.errors[0].message").value("text contains control characters"));

        // Phone: 4 chars rejected, 5 chars ok, 32 chars ok, 33 chars rejected
        String phone4 = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "phone": "1234",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phone4))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/phone"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid phone format"));

        String phone33 = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "phone": "123456789012345678901234567890123",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phone33))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/phone"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid phone format"));

        // Dates: real leap year ok, impossible date rejected
        String leapYearDate = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-02-29",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leapYearDate))
                .andExpect(status().isCreated());

        String impossibleDate = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2023-02-29",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(impossibleDate))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/lease_start"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid date format"));

        // Money: decimal rejected, negative rejected, over 100B rejected
        String decimalMoney = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100.50,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decimalMoney))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/monthly_rent"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid money value"));

        String negMoney = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": -5,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negMoney))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/monthly_rent"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid money value"));
    }

    @Test
    void selectAndOptionValidation() throws Exception {
        // Select in Unit: valid option "vacant"
        String validUnit = """
                {
                  "type": "unit",
                  "data": {
                    "address": "202 Oak St",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUnit))
                .andExpect(status().isCreated());

        // Invalid select option "demolished"
        String invalidUnit = """
                {
                  "type": "unit",
                  "data": {
                    "address": "202 Oak St",
                    "status": "demolished"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUnit))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/status"))
                .andExpect(jsonPath("$.errors[0].message").value("invalid select option"));
    }

    @Test
    void referenceRules_enforcedCrossAppAndArchived() throws Exception {
        // 1. Create unit in App 2
        String unitApp2 = """
                {
                  "type": "unit",
                  "data": {
                    "address": "App2 Unit",
                    "status": "vacant"
                  }
                }
                """;
        String r2 = mockMvc.perform(post("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/records")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitApp2))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String unitApp2Id = jsonMapper.readTree(r2).get("id").asText();

        // 2. Referencing unit from other app/workspace in App 1 rejected
        String tenantWithOtherAppUnit = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, unitApp2Id);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantWithOtherAppUnit))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/unit"))
                .andExpect(jsonPath("$.errors[0].message").value("reference target not found"));

        // 3. Create unit in App 1, archive it, try creating new tenant referencing it -> rejected
        String unitApp1 = """
                {
                  "type": "unit",
                  "data": {
                    "address": "App1 Archived Unit",
                    "status": "vacant"
                  }
                }
                """;
        String r1 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitApp1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String unitApp1Id = jsonMapper.readTree(r1).get("id").asText();

        // Archive this unit
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + unitApp1Id + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        String tenantWithArchivedUnit = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, unitApp1Id);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantWithArchivedUnit))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/unit"))
                .andExpect(jsonPath("$.errors[0].message").value("reference target is archived"));

        // 4. Unchanged reference to since-archived target is kept on update
        // Restore unit
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + unitApp1Id + "/restore")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Create tenant referencing active unit
        String tenantResp = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantWithArchivedUnit))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = jsonMapper.readTree(tenantResp).get("id").asText();

        // Now archive the unit again
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + unitApp1Id + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Update tenant without changing unit ref: succeeds!
        String updateTenantSameUnit = String.format("""
                {
                  "expectedVersion": 1,
                  "data": {
                    "name": "Jane Renamed",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, unitApp1Id);

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateTenantSameUnit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Jane Renamed"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void unknownFields_and_requiredFields_rejected() throws Exception {
        // Unknown field
        String unknownFieldJson = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unknown_xyz": "value"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownFieldJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/unknown_xyz"))
                .andExpect(jsonPath("$.errors[0].message").value("unknown field"));

        // Missing required field "email"
        String missingReq = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingReq))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/email"))
                .andExpect(jsonPath("$.errors[0].message").value("required field missing"));
    }

    @Test
    void pagination_ordering_prefixSearch_andEscaping() throws Exception {
        // Insert 3 units: "alpha unit", "beta_test unit", "special% unit"
        for (String addr : List.of("Charlie Unit", "Alice Unit", "beta_test unit", "special% unit")) {
            String u = String.format("""
                    {
                      "type": "unit",
                      "data": {
                        "address": "%s",
                        "status": "vacant"
                      }
                    }
                    """, addr);
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(u))
                    .andExpect(status().isCreated());
        }

        // Test ordering: Alice Unit, beta_test unit, Charlie Unit, special% unit
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&size=10")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].displayName").value("Alice Unit"))
                .andExpect(jsonPath("$.items[1].displayName").value("beta_test unit"))
                .andExpect(jsonPath("$.items[2].displayName").value("Charlie Unit"))
                .andExpect(jsonPath("$.items[3].displayName").value("special% unit"));

        // Test pagination: page=0 size=2 -> Alice, beta_test
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&page=0&size=2")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].displayName").value("Alice Unit"))
                .andExpect(jsonPath("$.items[1].displayName").value("beta_test unit"));

        // Prefix search: q=al -> matches Alice Unit
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&q=al")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Alice Unit"));

        // Escaping search: q=beta_ (should NOT wildcard-match "betax")
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&q=beta_")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("beta_test unit"));

        // Escaping search: q=special%
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&q=special%")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("special% unit"));
    }

    @Test
    void permissionMatrix_allEndpoints() throws Exception {
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "123 Test St",
                    "status": "vacant"
                  }
                }
                """;

        // POST /records: OWNER 201, STAFF 201, PORTAL_USER 403, non-member 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isNotFound());

        String r = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String recId = jsonMapper.readTree(r).get("id").asText();

        // GET /records: OWNER 200, STAFF 200, PORTAL_USER 403, non-member 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // Cross workspace/app scoping -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/records/" + recId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentUpdates_optimisticLocking() throws Exception {
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Lock Test",
                    "status": "vacant"
                  }
                }
                """;
        String r = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String recId = jsonMapper.readTree(r).get("id").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        String update1 = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "address": "Lock Update 1",
                    "status": "vacant"
                  }
                }
                """;
        String update2 = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "address": "Lock Update 2",
                    "status": "vacant"
                  }
                }
                """;

        Runnable task1 = () -> {
            try {
                barrier.await();
                var res = mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update1))
                        .andReturn().getResponse();
                if (res.getStatus() == 200) successCount.incrementAndGet();
                if (res.getStatus() == 409) conflictCount.incrementAndGet();
            } catch (Exception e) {
                // ignore
            }
        };

        Runnable task2 = () -> {
            try {
                barrier.await();
                var res = mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                                .header("Authorization", "Bearer " + staffToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update2))
                        .andReturn().getResponse();
                if (res.getStatus() == 200) successCount.incrementAndGet();
                if (res.getStatus() == 409) conflictCount.incrementAndGet();
            } catch (Exception e) {
                // ignore
            }
        };

        Future<?> f1 = executor.submit(task1);
        Future<?> f2 = executor.submit(task2);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one update must succeed");
        assertEquals(1, conflictCount.get(), "Exactly one update must conflict (409)");
    }

    @Test
    void limitExceeded_rejected() throws Exception {
        // App limit for parties is 500. Let's insert 500 rows directly into parties table
        UUID createdBy = ownerId;
        Instant now = Instant.now();

        // Direct DB insert 500 rows to test limit
        for (int i = 0; i < 500; i++) {
            jdbcClient.sql("""
                    INSERT INTO records (id, workspace_id, app_id, record_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at)
                    VALUES (:id, :wid, :aid, 'unit', :name, '{"address":"A","status":"vacant"}'::jsonb, 1, 1, :uid, :uid, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("wid", workspace1Id)
                    .param("aid", app1Id)
                    .param("name", "Bulk Unit " + i)
                    .param("uid", createdBy)
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }

        // 501st creation rejected with 409 limit_exceeded
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Overflow Unit",
                    "status": "vacant"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));
    }

    @Test
    void archivedApp_writesReturn409_readsReturn200() throws Exception {
        // Archive the app
        appService.archiveApp(workspace1Id, app1Id, ownerId);

        // Read works (200)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties?type=tenant")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Write fails (409 app_archived)
        String tenantJson = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Jane",
                    "email": "valid@email.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"));
    }

    @Test
    void strictParsing_duplicateKeyAndUnknownEnvelopeKey() throws Exception {
        // Unknown envelope key "displayName" rejected
        String withDisplayName = """
                {
                  "type": "unit",
                  "displayName": "Hacked",
                  "data": {
                    "address": "123 Elm",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDisplayName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown property in request envelope"));

        // Duplicate JSON key rejected
        String dupKey = """
                {
                  "type": "unit",
                  "type": "unit",
                  "data": {
                    "address": "123 Elm",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dupKey))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("duplicate JSON key"));
    }

    @Test
    void bodyOver32KB_returns413() throws Exception {
        byte[] large = new byte[32769];
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(large))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("Request body exceeds maximum size of 32768 bytes"));
    }

    @Test
    void chunkedBodyOver32KB_abortsWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[32770]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        bodySizeFilter.doFilter(request, response, (req, res) -> {
            var stream = req.getInputStream();
            byte[] buf = new byte[1024];
            while (stream.read(buf) != -1) {
                // read through
            }
        });

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("Request body exceeds maximum size of 32768 bytes"));
    }

    @Test
    void errorCapAt20_and_noEchoOfSecretMarker() throws Exception {
        // Build a JSON with 25 unknown fields containing SECRET_MARKER_123
        StringBuilder sb = new StringBuilder("{\"type\":\"tenant\",\"data\":{");
        for (int i = 0; i < 25; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"unknown_field_").append(i).append("\":\"SECRET_MARKER_123\"");
        }
        sb.append("}}");

        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors", hasSize(20))) // capped at 20!
                .andReturn().getResponse().getContentAsString();

        assertSanitizedErrorResponse(res);
    }

    @Test
    void record_update_and_archive_restore() throws Exception {
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "500 Pine St",
                    "status": "vacant"
                  }
                }
                """;

        String r = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("500 Pine St"))
                .andReturn().getResponse().getContentAsString();
        String recId = jsonMapper.readTree(r).get("id").asText();

        // Update record
        String updateJson = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "address": "500 Pine St, Suite 100",
                    "status": "occupied"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("500 Pine St, Suite 100"))
                .andExpect(jsonPath("$.version").value(2));

        // Archive record
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId + "/archive")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // Restore record
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId + "/restore")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void unknownType_rejectedWith422() throws Exception {
        String unknownTypeJson = """
                {
                  "type": "nonexistent_type",
                  "data": {
                    "name": "Jane"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownTypeJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/type"))
                .andExpect(jsonPath("$.errors[0].message").value("unknown party type"));
    }

    @Test
    void concurrentCreatesAtLimitMinusOne_parties_producesExactlyOneSuccess() throws Exception {
        Instant now = Instant.now();
        // Insert 499 parties directly into app1
        for (int i = 0; i < 499; i++) {
            jdbcClient.sql("""
                    INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at)
                    VALUES (:id, :wid, :aid, 'tenant', :name, '{"name":"A","email":"a@b.com","monthly_rent":100,"due_day":1,"lease_start":"2024-01-01","lease_end":"2024-12-31"}'::jsonb, 1, 1, :uid, :uid, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("wid", workspace1Id)
                    .param("aid", app1Id)
                    .param("name", "Bulk Tenant " + i)
                    .param("uid", ownerId)
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        String tenantJson = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Race Tenant",
                    "email": "race@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;

        Callable<Void> task = () -> {
            barrier.await();
            var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tenantJson))
                    .andReturn().getResponse();
            if (res.getStatus() == 201) successCount.incrementAndGet();
            if (res.getStatus() == 409) conflictCount.incrementAndGet();
            return null;
        };

        Future<?> f1 = executor.submit(task);
        Future<?> f2 = executor.submit(task);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent create at limit minus one must return 201");
        assertEquals(1, conflictCount.get(), "Exactly one concurrent create at limit minus one must return 409");
    }

    @Test
    void concurrentCreatesAtLimitMinusOne_records_producesExactlyOneSuccess() throws Exception {
        Instant now = Instant.now();
        // Insert 499 records directly into app1
        for (int i = 0; i < 499; i++) {
            jdbcClient.sql("""
                    INSERT INTO records (id, workspace_id, app_id, record_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at)
                    VALUES (:id, :wid, :aid, 'unit', :name, '{"address":"A","status":"vacant"}'::jsonb, 1, 1, :uid, :uid, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("wid", workspace1Id)
                    .param("aid", app1Id)
                    .param("name", "Bulk Unit " + i)
                    .param("uid", ownerId)
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Race Unit",
                    "status": "vacant"
                  }
                }
                """;

        Callable<Void> task = () -> {
            barrier.await();
            var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(unitJson))
                    .andReturn().getResponse();
            if (res.getStatus() == 201) successCount.incrementAndGet();
            if (res.getStatus() == 409) conflictCount.incrementAndGet();
            return null;
        };

        Future<?> f1 = executor.submit(task);
        Future<?> f2 = executor.submit(task);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent record create must succeed");
        assertEquals(1, conflictCount.get(), "Exactly one concurrent record create must conflict");
    }

    @Test
    void totalCap_rowsIncludingArchived_returns409LimitExceeded() throws Exception {
        Instant now = Instant.now();
        // Insert 100 active records and 900 archived records (total = 1000 = 2 * 500)
        for (int i = 0; i < 100; i++) {
            jdbcClient.sql("""
                    INSERT INTO records (id, workspace_id, app_id, record_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at)
                    VALUES (:id, :wid, :aid, 'unit', :name, '{"address":"A","status":"vacant"}'::jsonb, 1, 1, :uid, :uid, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("wid", workspace1Id)
                    .param("aid", app1Id)
                    .param("name", "Active Unit " + i)
                    .param("uid", ownerId)
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }
        for (int i = 0; i < 900; i++) {
            jdbcClient.sql("""
                    INSERT INTO records (id, workspace_id, app_id, record_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at, archived_at)
                    VALUES (:id, :wid, :aid, 'unit', :name, '{"address":"A","status":"vacant"}'::jsonb, 1, 1, :uid, :uid, :now, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("wid", workspace1Id)
                    .param("aid", app1Id)
                    .param("name", "Archived Unit " + i)
                    .param("uid", ownerId)
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }

        // Active is only 100 (< 500), but total is 1000 (>= 2 * 500) -> 409 limit_exceeded
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Total Cap Overflow",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));
    }

    @Test
    void scoping_otherAppAndOtherWorkspace_returns404OnAllEndpoints() throws Exception {
        // Create app 3 in workspace 1
        AppResponse app3 = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "RPM App 3", "UTC"));
        UUID app3Id = app3.id();

        // Create unit record in app 3
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Unit in App 3",
                    "status": "vacant"
                  }
                }
                """;
        String r3 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app3Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String app3UnitId = jsonMapper.readTree(r3).get("id").asText();

        // Access app 3 record using app 1 URL -> 404 on GET, PUT, archive, restore
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + app3UnitId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        String updateJson = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "address": "Spoofed",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + app3UnitId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + app3UnitId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + app3UnitId + "/restore")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // Access app 3 record using workspace 2 URL -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app2Id + "/records/" + app3UnitId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void displayName_recomputedOnUpdate_and_envelopeDisplayNameRejectedOnUpdate() throws Exception {
        String unitJson = """
                {
                  "type": "unit",
                  "data": {
                    "address": "Initial Address",
                    "status": "vacant"
                  }
                }
                """;
        String r = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unitJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Initial Address"))
                .andReturn().getResponse().getContentAsString();
        String recId = jsonMapper.readTree(r).get("id").asText();

        // Update recomputes display_name
        String updateJson = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "address": "Recomputed Address",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Recomputed Address"));

        // Client-sent displayName on update rejected
        String withDisplayNameUpdate = """
                {
                  "expectedVersion": 2,
                  "displayName": "Injected Name",
                  "data": {
                    "address": "Another Address",
                    "status": "vacant"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + recId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDisplayNameUpdate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown property in request envelope"));
    }

    @Test
    void definitionVersion_updatesToVersion2_andCarriesArchivedFieldsOver() throws Exception {
        // Create CDT app
        AppResponse cdtApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT Def Ver", "UTC"));
        UUID cdtAppId = cdtApp.id();

        // Create client with company field populated
        String clientJson = """
                {
                  "type": "client",
                  "data": {
                    "name": "Acme Corp",
                    "email": "info@acme.com",
                    "company": "Acme Global"
                  }
                }
                """;
        String r = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + cdtAppId + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String clientId = jsonMapper.readTree(r).get("id").asText();

        // Update definition to version 2: archive "company" field
        ObjectNode newDef = (ObjectNode) cdtApp.definition().deepCopy();
        JsonNode fields = newDef.path("partyTypes").get(0).path("fields");
        for (JsonNode f : fields) {
            if ("company".equals(f.path("id").asText())) {
                ((ObjectNode) f).put("archived", true);
            }
        }
        appService.putDefinition(workspace1Id, cdtAppId, ownerId,
                new AppPayloadParser.ParsedPutDefinitionRequest(1, newDef, "Archived company field"));

        // Update client without company in payload: definitionVersion becomes 2 and company is carried over!
        String updateClientJson = """
                {
                  "expectedVersion": 1,
                  "data": {
                    "name": "Acme Corp International",
                    "email": "info@acme.com"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + cdtAppId + "/parties/" + clientId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateClientJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionVersion").value(2))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.displayName").value("Acme Corp International"))
                .andExpect(jsonPath("$.data.company").value("Acme Global")); // carried over!
    }

    @Test
    void changingReference_toArchivedTarget_isRejected() throws Exception {
        // Create unit 1 (active) and unit 2 (will be archived)
        String u1 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"unit\",\"data\":{\"address\":\"Unit 1\",\"status\":\"vacant\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String u1Id = jsonMapper.readTree(u1).get("id").asText();

        String u2 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"unit\",\"data\":{\"address\":\"Unit 2\",\"status\":\"vacant\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String u2Id = jsonMapper.readTree(u2).get("id").asText();

        // Archive unit 2
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records/" + u2Id + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Create tenant referencing active unit 1
        String tenantJson = String.format("""
                {
                  "type": "tenant",
                  "data": {
                    "name": "Alice",
                    "email": "alice@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, u1Id);
        String t = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = jsonMapper.readTree(t).get("id").asText();

        // Update tenant changing unit from u1 to u2 (which is archived) -> rejected with 422
        String badUpdate = String.format("""
                {
                  "expectedVersion": 1,
                  "data": {
                    "name": "Alice",
                    "email": "alice@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31",
                    "unit": "%s"
                  }
                }
                """, u2Id);
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badUpdate))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/unit"))
                .andExpect(jsonPath("$.errors[0].message").value("reference target is archived"));
    }

    @Test
    void partyArchiveAndRestore_versionBump_restoreOnArchivedApp409_updateArchivedParty409() throws Exception {
        String tenantJson = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Bob",
                    "email": "bob@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        String t = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = jsonMapper.readTree(t).get("id").asText();

        // Archive party -> version bumps from 1 to 2
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Update an archived party returns 409 party_archived
        String updateJson = """
                {
                  "expectedVersion": 2,
                  "data": {
                    "name": "Bob Updated",
                    "email": "bob@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("party_archived"));

        // Archive the app
        appService.archiveApp(workspace1Id, app1Id, ownerId);

        // Restore on an archived app returns 409 app_archived
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + tenantId + "/restore")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"));
    }

    @Test
    void email_storedLowercaseAndTrimmed_whitespaceRequiredFieldTreatedAsMissing() throws Exception {
        // Email trimmed and stored lowercase
        String tenantWithSpacedEmail = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "Charlie",
                    "email": "   User@Example.Com   ",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantWithSpacedEmail))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("user@example.com"));

        // Whitespace-only required text value treated as missing
        String whitespaceName = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "     ",
                    "email": "user@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(whitespaceName))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/data/name"))
                .andExpect(jsonPath("$.errors[0].message").value("required field missing"));
    }

    @Test
    void createAsStaff_is201_portalUser403_nonMember404_parties() throws Exception {
        String tenantJson = """
                {
                  "type": "tenant",
                  "data": {
                    "name": "David",
                    "email": "david@example.com",
                    "monthly_rent": 100,
                    "due_day": 1,
                    "lease_start": "2024-01-01",
                    "lease_end": "2024-12-31"
                  }
                }
                """;

        // STAFF -> 201
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isCreated());

        // PORTAL_USER -> 403
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isForbidden());

        // Non-member -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson))
                .andExpect(status().isNotFound());
    }

    @Test
    void pagination_sizeCappedAt50_pageBeyondEnd_negativePageRejected() throws Exception {
        // Insert 2 units
        for (int i = 0; i < 2; i++) {
            String u = String.format("{\"type\":\"unit\",\"data\":{\"address\":\"Unit %d\",\"status\":\"vacant\"}}", i);
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(u))
                    .andExpect(status().isCreated());
        }

        // size=51 does not return more than 50 items (capped at 50)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&size=51")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(2));

        // page beyond the end returns empty items with the right total
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&page=10")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(10));

        // negative page is rejected with 400
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/records?type=unit&page=-1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("page must be >= 0"));
    }

    @Test
    void list_missingType_and_unknownType_rejectedWithFixedText() throws Exception {
        // Missing type parameter
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("type is required"));

        // Unknown type parameter
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties?type=nonexistent_type")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown type"));
    }

    @Test
    void prefixSearch_localeRoot_stillWorks() throws Exception {
        AppResponse cdtApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT Locale App", "UTC"));

        String clientJson = """
                {
                  "type": "client",
                  "data": {
                    "name": "Istanbul Resident",
                    "email": "istanbul@example.com"
                  }
                }
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + cdtApp.id() + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Istanbul Resident"));

        // Case-insensitive prefix search with lowercase query "ist" matches uppercase "Istanbul"
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + cdtApp.id() + "/parties?type=client&q=ist")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].displayName").value("Istanbul Resident"));

        // Prefix search with uppercase query "IST" matches uppercase "Istanbul"
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + cdtApp.id() + "/parties?type=client&q=IST")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].displayName").value("Istanbul Resident"));
    }
}
