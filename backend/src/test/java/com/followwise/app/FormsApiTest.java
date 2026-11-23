package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.appdef.FieldConstraints;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyService;
import com.followwise.security.TokenService;
import com.followwise.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class FormsApiTest extends AbstractIntegrationTest {

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

    private UUID createRentalApp() {
        AppResponse app = appService.createApp(
                workspace1Id,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "Rental Property Manager", "America/New_York")
        );
        return app.id();
    }

    private UUID createClientDocApp() {
        AppResponse app = appService.createApp(
                workspace1Id,
                ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "Client Document Tracker", "America/New_York")
        );
        return app.id();
    }

    @Test
    void testFormsRequiresAuth() throws Exception {
        UUID appId = createRentalApp();
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testFormsPermissionMatrix() throws Exception {
        UUID appId = createRentalApp();

        // OWNER -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // STAFF -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // PORTAL_USER -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // non-member -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // unknown app -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + UUID.randomUUID() + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // app belonging to other workspace -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void testFormsRentalTemplateSnapshot() throws Exception {
        UUID appId = createRentalApp();

        MvcResult result = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode actualTree = jsonMapper.readTree(responseBody);

        ClassPathResource expectedResource = new ClassPathResource("fixtures/forms/rental-property-manager-forms.json");
        try (InputStream is = expectedResource.getInputStream()) {
            JsonNode expectedTree = jsonMapper.readTree(is);
            assertEquals(expectedTree, actualTree);
        }
    }

    @Test
    void testFormsClientDocumentTemplateSnapshot() throws Exception {
        UUID appId = createClientDocApp();

        MvcResult result = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode actualTree = jsonMapper.readTree(responseBody);

        ClassPathResource expectedResource = new ClassPathResource("fixtures/forms/client-document-tracker-forms.json");
        try (InputStream is = expectedResource.getInputStream()) {
            JsonNode expectedTree = jsonMapper.readTree(is);
            assertEquals(expectedTree, actualTree);
        }
    }

    @Test
    void testFormsArchivedTypesAndChecklistsAndSchedulesOmitted() throws Exception {
        UUID appId = createRentalApp();
        AppResponse currentApp = appService.getApp(workspace1Id, appId, ownerId);

        ObjectNode newDef = (ObjectNode) currentApp.definition().deepCopy();
        // Archive the charge schedule 'late_fee'
        ArrayNode schedules = (ArrayNode) newDef.get("chargeSchedules");
        for (JsonNode s : schedules) {
            if ("late_fee".equals(s.path("id").asText())) {
                ((ObjectNode) s).put("archived", true);
            }
        }

        appService.putDefinition(
                workspace1Id,
                appId,
                ownerId,
                new AppPayloadParser.ParsedPutDefinitionRequest(1, newDef, "archive late_fee")
        );

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeSchedules", hasSize(1)))
                .andExpect(jsonPath("$.chargeSchedules[0].id").value("monthly_rent"));
    }

    @Test
    void testFormsArchivedFieldsAndOptions() throws Exception {
        UUID appId = createRentalApp();
        AppResponse currentApp = appService.getApp(workspace1Id, appId, ownerId);

        ObjectNode newDef = (ObjectNode) currentApp.definition().deepCopy();
        // Archive field 'phone' on 'tenant'
        ArrayNode partyTypes = (ArrayNode) newDef.get("partyTypes");
        ArrayNode fields = (ArrayNode) partyTypes.get(0).get("fields");
        for (JsonNode f : fields) {
            if ("phone".equals(f.path("id").asText())) {
                ((ObjectNode) f).put("archived", true);
            }
        }
        // Archive option 'vacant' on unit.status
        ArrayNode recordTypes = (ArrayNode) newDef.get("recordTypes");
        ArrayNode unitFields = (ArrayNode) recordTypes.get(0).get("fields");
        for (JsonNode f : unitFields) {
            if ("status".equals(f.path("id").asText())) {
                ArrayNode options = (ArrayNode) f.get("options");
                for (JsonNode opt : options) {
                    if ("vacant".equals(opt.path("id").asText())) {
                        ((ObjectNode) opt).put("archived", true);
                    }
                }
            }
        }

        appService.putDefinition(
                workspace1Id,
                appId,
                ownerId,
                new AppPayloadParser.ParsedPutDefinitionRequest(1, newDef, "archive phone and vacant")
        );

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                // phone is still present in fields, but hidden=true and readOnly=true
                .andExpect(jsonPath("$.partyTypes[0].fields[?(@.id == 'phone')].hidden").value(true))
                .andExpect(jsonPath("$.partyTypes[0].fields[?(@.id == 'phone')].readOnly").value(true))
                // phone is omitted from listColumns
                .andExpect(jsonPath("$.partyTypes[0].listColumns", not(hasItem("phone"))))
                // vacant option is flagged archived=true
                .andExpect(jsonPath("$.recordTypes[0].fields[?(@.id == 'status')].options[?(@.id == 'vacant')].archived").value(true))
                .andExpect(jsonPath("$.recordTypes[0].fields[?(@.id == 'status')].options[?(@.id == 'occupied')].archived").value(false));
    }

    @Test
    void testFormsListColumnsAndDisplayNameFieldRules() throws Exception {
        UUID appId = createRentalApp();
        AppResponse currentApp = appService.getApp(workspace1Id, appId, ownerId);

        ObjectNode newDef = (ObjectNode) currentApp.definition().deepCopy();
        // In rental template, tenant fields are: name(text), email, phone, monthly_rent, due_day, lease_start, lease_end, unit
        // Archive 'name'
        ArrayNode partyTypes = (ArrayNode) newDef.get("partyTypes");
        ArrayNode fields = (ArrayNode) partyTypes.get(0).get("fields");
        for (JsonNode f : fields) {
            if ("name".equals(f.path("id").asText())) {
                ((ObjectNode) f).put("archived", true);
            }
        }
        // Add a second text field 'nickname' (not archived)
        ObjectNode nicknameField = jsonMapper.createObjectNode()
                .put("id", "nickname")
                .put("label", "Nickname")
                .put("type", "text")
                .put("required", false);
        fields.add(nicknameField);

        appService.putDefinition(
                workspace1Id,
                appId,
                ownerId,
                new AppPayloadParser.ParsedPutDefinitionRequest(1, newDef, "archive name and add nickname")
        );

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                // displayNameField should be 'nickname' (first non-archived text field)
                .andExpect(jsonPath("$.partyTypes[0].displayNameField").value("nickname"))
                // listColumns should start with 'nickname', then other non-archived fields up to 4 total
                .andExpect(jsonPath("$.partyTypes[0].listColumns[0]").value("nickname"))
                .andExpect(jsonPath("$.partyTypes[0].listColumns", hasSize(4)))
                .andExpect(jsonPath("$.partyTypes[0].listColumns", not(hasItem("name"))));
    }

    @Test
    void testFormsConstraintsEqualConstants() throws Exception {
        UUID appId = createRentalApp();

        MvcResult result = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode fields = root.path("partyTypes").get(0).path("fields");
        for (JsonNode f : fields) {
            String fid = f.path("id").asText();
            JsonNode c = f.path("constraints");
            switch (fid) {
                case "name" -> {
                    assertEquals(FieldConstraints.TEXT_MIN_LENGTH, c.path("minLength").asInt());
                    assertEquals(FieldConstraints.TEXT_MAX_LENGTH, c.path("maxLength").asInt());
                }
                case "email" -> assertEquals(FieldConstraints.EMAIL_MAX_LENGTH, c.path("maxLength").asInt());
                case "phone" -> {
                    assertEquals(FieldConstraints.PHONE_MIN_LENGTH, c.path("minLength").asInt());
                    assertEquals(FieldConstraints.PHONE_MAX_LENGTH, c.path("maxLength").asInt());
                }
                case "due_day" -> {
                    assertEquals(FieldConstraints.INTEGER_MIN, c.path("min").asLong());
                    assertEquals(FieldConstraints.INTEGER_MAX, c.path("max").asLong());
                }
                case "monthly_rent" -> {
                    assertEquals(FieldConstraints.MONEY_MIN_CENTS, c.path("min").asLong());
                    assertEquals(FieldConstraints.MONEY_MAX_CENTS, c.path("max").asLong());
                }
                case "lease_start" -> assertEquals(FieldConstraints.DATE_FORMAT, c.path("format").asText());
            }
        }
    }

    @Test
    void testFormsArchivedAppStillReadable() throws Exception {
        UUID appId = createRentalApp();

        // Archive app
        appService.archiveApp(workspace1Id, appId, ownerId);

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("Rental Property Manager"));
    }

    @Test
    void testFormsNoPersonalData() throws Exception {
        UUID appId = createRentalApp();

        // Add a party with personal info
        partyService.createParty(
                workspace1Id,
                appId,
                ownerId,
                "tenant",
                jsonMapper.createObjectNode()
                        .put("name", "John Doe Secret")
                        .put("email", "johndoe@secretpersonaldomain.com")
                        .put("phone", "+1 555-987-6543")
                        .put("monthly_rent", 150000)
                        .put("due_day", 1)
                        .put("lease_start", "2024-01-01")
                        .put("lease_end", "2024-12-31")
        );

        MvcResult result = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains("johndoe@secretpersonaldomain.com"));
        assertFalse(raw.contains("John Doe Secret"));
        assertFalse(raw.contains("555-987-6543"));
    }

    @Test
    void testFormsConstraintsMatchFieldValidator() throws Exception {
        UUID appId = createRentalApp();

        // Fetch /forms and check maxLength for 'name'
        MvcResult res = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/forms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        int maxLength = root.path("partyTypes").get(0).path("fields").get(0).path("constraints").path("maxLength").asInt();
        assertEquals(FieldConstraints.TEXT_MAX_LENGTH, maxLength);

        // Value of length 500 should succeed
        String validName = "A".repeat(maxLength);
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(jsonMapper.createObjectNode()
                                .put("type", "tenant")
                                .set("data", jsonMapper.createObjectNode()
                                        .put("name", validName)
                                        .put("email", "tenant@example.com")
                                        .put("monthly_rent", 100000)
                                        .put("due_day", 1)
                                        .put("lease_start", "2024-01-01")
                                        .put("lease_end", "2024-12-31")))))
                .andExpect(status().isCreated());

        // Value of length 501 should fail 422
        String invalidName = "A".repeat(maxLength + 1);
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/parties")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(jsonMapper.createObjectNode()
                                .put("type", "tenant")
                                .set("data", jsonMapper.createObjectNode()
                                        .put("name", invalidName)
                                        .put("email", "tenant2@example.com")
                                        .put("monthly_rent", 100000)
                                        .put("due_day", 1)
                                        .put("lease_start", "2024-01-01")
                                        .put("lease_end", "2024-12-31")))))
                .andExpect(status().isUnprocessableEntity());
    }
}
