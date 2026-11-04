package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.appdef.TemplateRegistry;
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
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AppApiTest extends AbstractIntegrationTest {

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
    private TemplateRegistry templateRegistry;

    @Autowired
    private AppPayloadParser appPayloadParser;

    @Autowired
    private AppStore appStore;

    @Autowired
    private AppService appService;

    @Autowired
    private BodySizeFilter bodySizeFilter;

    @Autowired
    private JdbcClient jdbcClient;

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
        jdbcClient.sql("DELETE FROM app_definition_versions").update();
        jdbcClient.sql("DELETE FROM apps").update();
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

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

    @AfterEach
    void tearDown() {
        jdbcClient.sql("DELETE FROM app_definition_versions").update();
        jdbcClient.sql("DELETE FROM apps").update();
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listTemplates_and_getTemplate_success() throws Exception {
        mockMvc.perform(get("/api/templates")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].key").value("client-document-tracker"))
                .andExpect(jsonPath("$[0].name").value("Client Document Tracker"))
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[1].key").value("rental-property-manager"))
                .andExpect(jsonPath("$[1].name").value("Rental Property Manager"));

        mockMvc.perform(get("/api/templates/rental-property-manager")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.template").value("rental-property-manager"));

        // Unknown template -> 404
        mockMvc.perform(get("/api/templates/unknown-template")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Invalid key format -> 404
        mockMvc.perform(get("/api/templates/INVALID_KEY!")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void createApp_fromBothTemplates_andRoundTrip() throws Exception {
        String bodyRental = """
                {"templateKey":"rental-property-manager","name":"My Rental App","timezone":"America/New_York"}
                """;

        String rentalRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyRental))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("My Rental App"))
                .andExpect(jsonPath("$.templateKey").value("rental-property-manager"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.definition.name").value("My Rental App"))
                .andExpect(jsonPath("$.definition.timezone").value("America/New_York"))
                .andReturn().getResponse().getContentAsString();

        JsonNode rentalNode = appPayloadParser.getJsonMapper().readTree(rentalRes);
        String rentalAppId = rentalNode.get("id").asText();

        // Round trip test: JsonNode equality with DB fetch
        AppResponse appFromDb = appService.getApp(workspace1Id, UUID.fromString(rentalAppId), ownerId);
        assertEquals(rentalNode.get("definition"), appFromDb.definition());

        String bodyClient = """
                {"templateKey":"client-document-tracker","name":"Doc Tracker App"}
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyClient))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Doc Tracker App"))
                .andExpect(jsonPath("$.templateKey").value("client-document-tracker"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.definition.name").value("Doc Tracker App"));
    }

    @Test
    void permissionMatrix_enforcedAcrossAllEndpoints() throws Exception {
        String body = """
                {"templateKey":"rental-property-manager","name":"Test App"}
                """;
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        // Non-member gets 404 everywhere
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{}}"))
                .andExpect(status().isNotFound());

        // Portal user gets 403 everywhere
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/archive")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Staff has VIEW_APP -> read 200, write 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/archive")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void scoping_appOfWorkspaceB_underWorkspaceA_returns404() throws Exception {
        // Create app in workspace 2 by nonMember (who is owner of workspace 2)
        String body = """
                {"templateKey":"rental-property-manager","name":"App W2"}
                """;
        String res = mockMvc.perform(post("/api/workspaces/" + workspace2Id + "/apps")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String app2Id = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        // Query app2 under workspace 1 by owner of workspace 1 -> 404!
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app2Id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app2Id + "/versions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void putDefinition_createsVersion2_updatesName() throws Exception {
        String body = """
                {"templateKey":"rental-property-manager","name":"Initial Name"}
                """;
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = appPayloadParser.getJsonMapper().readTree(res);
        String appId = created.get("id").asText();
        ObjectNode def = (ObjectNode) created.get("definition").deepCopy();
        def.put("name", "Renamed via Definition");

        String putBody = """
                {
                  "expectedVersion": 1,
                  "definition": %s,
                  "note": "Update app name"
                }
                """.formatted(def.toString());

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.name").value("Renamed via Definition"))
                .andExpect(jsonPath("$.definition.name").value("Renamed via Definition"));

        // Verify version history
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/versions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].note").value("Update app name"));

        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/versions/2")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.definition.name").value("Renamed via Definition"));
    }

    @Test
    void putDefinition_staleExpectedVersion_returns409WithCurrentVersion() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Stale Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        String putBody = """
                {
                  "expectedVersion": 5,
                  "definition": {"name":"Whatever"}
                }
                """;

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("stale_version"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void putDefinition_identicalDefinition_returns200WithoutCreatingVersion() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Identical Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = appPayloadParser.getJsonMapper().readTree(res);
        String appId = created.get("id").asText();
        JsonNode def = created.get("definition");

        String putBody = """
                {
                  "expectedVersion": 1,
                  "definition": %s
                }
                """.formatted(def.toString());

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1));

        assertEquals(1, appStore.countVersions(UUID.fromString(appId)));
    }

    @Test
    void putDefinition_invalidDefinition_returns422_withAtMost20Errors() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Invalid Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        String putBody = """
                {
                  "expectedVersion": 1,
                  "definition": {
                    "schemaVersion": 1,
                    "template": "rental-property-manager",
                    "name": "Invalid Def",
                    "currency": "EUR"
                  }
                }
                """;

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.errors", hasSize(lessThanOrEqualTo(20))))
                .andExpect(jsonPath("$.trace").doesNotExist());

        assertEquals(1, appStore.countVersions(UUID.fromString(appId)));
    }

    @Test
    void putDefinition_transitionViolation_returns422() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Transition Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = appPayloadParser.getJsonMapper().readTree(res);
        String appId = created.get("id").asText();
        ObjectNode def = (ObjectNode) created.get("definition").deepCopy();
        // Remove partyTypes to trigger transition violation
        def.remove("partyTypes");

        String putBody = """
                {
                  "expectedVersion": 1,
                  "definition": %s
                }
                """.formatted(def.toString());

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void activeAppLimit_and_slotReuseAfterArchive_and_totalAppLimit() throws Exception {
        // Create 5 active apps (max = 5)
        List<String> appIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"App " + i + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            appIds.add(appPayloadParser.getJsonMapper().readTree(res).get("id").asText());
        }

        // 6th active app -> 409 limit_exceeded
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"App 6\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));

        // Archive app 1 -> frees an active slot
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appIds.get(0) + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Now creating 6th app succeeds!
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"App 6 New\"}"))
                .andExpect(status().isCreated());

        // Verify archived app cannot be edited
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appIds.get(0) + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"));
    }

    @Test
    void versionLimit_returns409_version_limit() {
        // Insert an app and simulate 200 versions
        UUID appId = UUID.randomUUID();
        Instant now = Instant.now();
        appStore.insertApp(appId, workspace1Id, "App For Versions", "rental-property-manager", 200, now);

        JsonNode tmpl = templateRegistry.getTemplate("rental-property-manager").get();
        ObjectNode copy = (ObjectNode) tmpl.deepCopy();
        copy.put("name", "App For Versions");
        String def = copy.toString();

        for (int v = 1; v <= 200; v++) {
            appStore.insertVersion(UUID.randomUUID(), appId, v, def, null, ownerId, now);
        }

        copy.put("name", "App Version 201");
        AppPayloadParser.ParsedPutDefinitionRequest req = new AppPayloadParser.ParsedPutDefinitionRequest(
                200, copy, "attempt 201"
        );

        AppConflictException ex = assertThrows(AppConflictException.class, () ->
                appService.putDefinition(workspace1Id, appId, ownerId, req)
        );
        assertEquals("version_limit", ex.getConflictType());
    }

    @Test
    void bodyOver80KB_returns413() throws Exception {
        byte[] large = new byte[81921];
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(large))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("Request body exceeds maximum size of 81920 bytes"));
    }

    @Test
    void chunkedBodyOver80KB_abortsWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workspaces/" + workspace1Id + "/apps");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[81925]); // > 81920 bytes
        // No Content-Length set (chunked)

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        bodySizeFilter.doFilter(request, response, (req, res) -> {
            // Read until exception
            byte[] buf = new byte[1024];
            var is = req.getInputStream();
            while (is.read(buf) != -1) {
            }
        });

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("Request body exceeds maximum size of 81920 bytes"));
    }

    @Test
    void duplicateKeyInDefinition_rejected() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Dup Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        String dupJson = """
                {
                  "expectedVersion": 1,
                  "definition": {
                    "schemaVersion": 1,
                    "name": "One",
                    "name": "Two"
                  }
                }
                """;

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dupJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("duplicate JSON key"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void invalidEnvelope_and_badInputs_rejected() throws Exception {
        // Unknown key in create envelope -> 400
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Ok\",\"unknownKey\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("unknown property")));

        // Invalid timezone in create -> 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Ok\",\"timezone\":\"Mars/Base\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/timezone"));

        // Note over 500 characters in PUT definition -> 400
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Note Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        String longNote = "a".repeat(501);
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{},\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("note must be at most 500 characters")));
    }

    @Test
    void parallelCreates_whenFourAppsExist_exactlyOneSucceeds() throws Exception {
        // Create 4 apps first
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"App " + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> createCall = () -> {
            startLatch.await();
            return mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Parallel App\"}"))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(createCall);
        Future<Integer> f2 = executor.submit(createCall);

        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        assertTrue((s1 == 201 && s2 == 409) || (s1 == 409 && s2 == 201),
                "Expected one 201 and one 409, got: " + s1 + " and " + s2);
        assertEquals(5, appStore.countActiveApps(workspace1Id));
    }

    @Test
    void concurrentSaves_withSameExpectedVersion_exactlyOneSucceeds() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Concurrent Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = appPayloadParser.getJsonMapper().readTree(res);
        String appId = created.get("id").asText();

        ObjectNode def1 = (ObjectNode) created.get("definition").deepCopy();
        def1.put("name", "Branch 1");
        ObjectNode def2 = (ObjectNode) created.get("definition").deepCopy();
        def2.put("name", "Branch 2");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> save1 = () -> {
            startLatch.await();
            return mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"definition\":" + def1 + "}"))
                    .andReturn().getResponse().getStatus();
        };

        Callable<Integer> save2 = () -> {
            startLatch.await();
            return mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"definition\":" + def2 + "}"))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(save1);
        Future<Integer> f2 = executor.submit(save2);

        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        assertTrue((s1 == 200 && s2 == 409) || (s1 == 409 && s2 == 200),
                "Expected one 200 and one 409, got: " + s1 + " and " + s2);
    }

    @Test
    void createApp_isAtomic_rollsBackOnFailure() {
        // Attempt create with invalid template
        AppPayloadParser.ParsedCreateAppRequest req = new AppPayloadParser.ParsedCreateAppRequest(
                "non-existent-template", "Atomic App", null
        );

        assertThrows(AppNotFoundException.class, () ->
                appService.createApp(workspace1Id, ownerId, req)
        );

        assertEquals(0, appStore.countTotalApps(workspace1Id));
    }

    @Test
    void putDefinition_onArchivedApp_returns409_app_archived() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Archived Put Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{\"name\":\"New\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void putDefinition_nestedDeeperThanLimit_rejectedWithFixedTextProblemJson() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Nesting Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        // 25 levels of nesting: { "expectedVersion": 1, "definition": { "a": { "a": ... } } }
        StringBuilder nested = new StringBuilder("{\"expectedVersion\":1,\"definition\":");
        for (int i = 0; i < 25; i++) {
            nested.append("{\"lvl\":");
        }
        nested.append("1");
        for (int i = 0; i < 25; i++) {
            nested.append("}");
        }
        nested.append("}");

        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nested.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("maximum nesting depth exceeded"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void everyErrorResponseType_containsNoStackTrace_noException_andNoEchoedMarker() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Marker Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        // 1. 400 Bad Request (unknown property in envelope with SECRET_MARKER_123)
        String r400 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Ok\",\"SECRET_MARKER_123\":1}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r400);

        // 2. 403 Forbidden (staff trying to create app)
        String r403 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"SECRET_MARKER_123\"}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r403);

        // 3. 404 Not Found (unknown template key with SECRET_MARKER_123)
        String r404 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"SECRET_MARKER_123\",\"name\":\"Valid\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r404);

        // 4. 409 stale_version
        String r409Stale = mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":99,\"definition\":{},\"note\":\"SECRET_MARKER_123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("stale_version"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r409Stale);

        // 5. 409 limit_exceeded (fill up to 5 apps, 6th fails)
        for (int i = 2; i <= 5; i++) {
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"App " + i + "\"}"))
                    .andExpect(status().isCreated());
        }
        String r409Limit = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"SECRET_MARKER_123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r409Limit);

        // 6. 409 app_archived
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        String r409Archived = mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{},\"note\":\"SECRET_MARKER_123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r409Archived);

        // 7. 409 version_limit (tested via service to verify sanitized conflict format)
        AppConflictException verEx = new AppConflictException("version_limit", "Maximum number of definition versions reached");
        ProblemDetail pdVer = new AppExceptionHandler().handleConflictException(verEx);
        assertFalse(pdVer.getDetail().contains("SECRET_MARKER_123"));
        assertFalse(pdVer.getDetail().toLowerCase().contains("exception"));

        // 8. 413 Payload Too Large
        String r413 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[81921]))
                .andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r413);

        // 9. 422 Unprocessable Entity (label with invalid characters containing SECRET_MARKER_123)
        String r422 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"<SECRET_MARKER_123>\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertSanitizedErrorResponse(r422);
    }

    private void assertSanitizedErrorResponse(String responseBody) {
        assertFalse(responseBody.toLowerCase().contains("exception"), "Error response must not contain 'Exception': " + responseBody);
        assertFalse(responseBody.contains("stackTrace"), "Error response must not contain stackTrace: " + responseBody);
        assertFalse(responseBody.contains("trace"), "Error response must not contain trace: " + responseBody);
        assertFalse(responseBody.contains("SECRET_MARKER_123"), "Error response must not echo SECRET_MARKER_123: " + responseBody);
    }

    @Test
    void archivedApps_excludedFromListByDefault_andIncludedWithQueryParam() throws Exception {
        String res1 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Active App\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String app1Id = appPayloadParser.getJsonMapper().readTree(res1).get("id").asText();

        String res2 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Archived App\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String app2Id = appPayloadParser.getJsonMapper().readTree(res2).get("id").asText();

        // Archive app2
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app2Id + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Default list: only app1
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(app1Id))
                .andExpect(jsonPath("$[0].name").value("Active App"));

        // With archived=true: both apps
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps?archived=true")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getArchivedApp_worksForOwnerAndStaff() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Archived Read Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // GET by owner succeeds with 200 and definition
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.definition.name").value("Archived Read Test"));

        // GET by staff succeeds with 200 and definition
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + appId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.definition.name").value("Archived Read Test"));
    }

    @Test
    void rejectedInputs_eachReturnsExpectedStatus() throws Exception {
        String res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Input Validation Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appId = appPayloadParser.getJsonMapper().readTree(res).get("id").asText();

        // 1. Note longer than 500 chars -> 400
        String longNote = "x".repeat(501);
        mockMvc.perform(put("/api/workspaces/" + workspace1Id + "/apps/" + appId + "/definition")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"definition\":{},\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 2. Invalid timezone on create -> 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Bad TZ\",\"timezone\":\"Not/Real\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        // 3. Unknown envelope key on create -> 400
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"rental-property-manager\",\"name\":\"Bad Env\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 4. Unknown template key on create -> 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"non-existent-template-key\",\"name\":\"Unknown Tmpl\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void appStore_countActiveApps_exercisesSqlAndFiltering() {
        assertEquals(0, appStore.countActiveApps(workspace1Id));
        UUID aid = UUID.randomUUID();
        Instant now = Instant.now();
        appStore.insertApp(aid, workspace1Id, "App Active Count", "rental-property-manager", 1, now);
        assertEquals(1, appStore.countActiveApps(workspace1Id));

        appStore.archiveApp(aid, workspace1Id, now);
        assertEquals(0, appStore.countActiveApps(workspace1Id));
        assertEquals(1, appStore.countTotalApps(workspace1Id));
    }
}
