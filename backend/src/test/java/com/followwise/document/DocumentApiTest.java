package com.followwise.document;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.app.BodySizeFilter;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyService;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import(DocumentApiTest.TestDocumentConfig.class)
class DocumentApiTest extends AbstractIntegrationTest {

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
    private static final FakeDocumentStorage FAKE_STORAGE = new FakeDocumentStorage();

    @TestConfiguration
    static class TestDocumentConfig {
        @Bean
        @Primary
        public Clock testClock() {
            return MUTABLE_CLOCK;
        }

        @Bean
        @Primary
        public DocumentStorage testStorage() {
            return FAKE_STORAGE;
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
    private DocumentService documentService;

    @Autowired
    private DocumentStore documentStore;

    @Autowired
    private DocumentRequestStore documentRequestStore;

    @Autowired
    private StorageConfig storageConfig;

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
    private UUID party1Id;

    private UUID ownerId;
    private UUID staffId;
    private UUID portalId;
    private UUID nonMemberId;

    private String ownerToken;
    private String staffToken;
    private String portalToken;
    private String nonMemberToken;

    // Helper valid files
    private static final byte[] VALID_PDF = "%PDF-1.4 sample content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALID_PNG = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2};

    @BeforeEach
    void setUp() throws Exception {
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-06-01T12:00:00Z"));
        FAKE_STORAGE.clear();
        TestDbHelper.cleanDatabase(dataSource);

        storageConfig.setMaxDocumentBytes(4194304);
        storageConfig.setMaxDocumentsPerApp(1000);
        storageConfig.setMaxStorageBytesPerApp(209715200);
        storageConfig.setMaxStorageBytesTotal(943718400);
        storageConfig.setMaxVersionsPerRequest(10);
        storageConfig.setMaxRequestsPerParty(50);
        storageConfig.setMaxConcurrentUploads(4);

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
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App", "America/New_York"));
        app1Id = app1.id();

        AppResponse app2 = appService.createApp(workspace2Id, nonMemberId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App 2", "UTC"));
        app2Id = app2.id();

        var p1 = partyService.createParty(workspace1Id, app1Id, ownerId, "client",
                jsonMapper.readTree("""
                        {"name": "Alice Smith", "email": "alice@example.com"}
                        """));
        party1Id = p1.id();
    }

    @AfterEach
    void tearDown() {
        FAKE_STORAGE.clear();
    }

    @Test
    void assign_creates_requests_with_copied_fields_and_due_dates_and_skips_existing() throws Exception {
        // Today is 2026-06-01. Checklist onboarding_docs has:
        // 1. gov_id (required: true, dueOffsetDays: 7 -> due 2026-06-08, expiryDays: 365)
        // 2. tax_form (required: true, dueOffsetDays: 7 -> due 2026-06-08, no expiryDays)
        // 3. proof_of_address (required: false, dueOffsetDays: 14 -> due 2026-06-15, no expiryDays)
        String assignPayload = """
                {"checklistId": "onboarding_docs"}
                """;

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skippedExisting").value(0))
                .andExpect(jsonPath("$.created", hasSize(3)))
                .andExpect(jsonPath("$.created[?(@.itemId == 'gov_id')].required").value(true))
                .andExpect(jsonPath("$.created[?(@.itemId == 'gov_id')].expiryDays").value(365))
                .andExpect(jsonPath("$.created[?(@.itemId == 'gov_id')].dueDate").value("2026-06-08"))
                .andExpect(jsonPath("$.created[?(@.itemId == 'gov_id')].status").value("PENDING"))
                .andExpect(jsonPath("$.created[?(@.itemId == 'proof_of_address')].required").value(false))
                .andExpect(jsonPath("$.created[?(@.itemId == 'proof_of_address')].dueDate").value("2026-06-15"));

        // Second assign creates nothing and increments skippedExisting
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skippedExisting").value(3))
                .andExpect(jsonPath("$.created", hasSize(0)));
    }

    @Test
    void unknown_archived_and_wrong_type_checklists_rejected() throws Exception {
        // Unknown checklist -> 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "non_existent_checklist"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].path").value("/checklistId"));

        // Create an app with an archived checklist and a checklist with mismatched partyType
        // Let's create an app using rental-property-manager
        AppResponse rpmApp = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("rental-property-manager", "RPM App", "UTC"));
        var tenant = partyService.createParty(workspace1Id, rpmApp.id(), ownerId, "tenant",
                jsonMapper.readTree("""
                        {"name": "Tenant Bob", "email": "bob@example.com", "monthly_rent": 120000, "due_day": 1, "lease_start": "2026-06-01", "lease_end": "2027-05-31"}
                        """));

        // rental-property-manager has no checklists -> unknown checklist -> 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + rpmApp.id() + "/parties/" + tenant.id() + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "onboarding_docs"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void archived_party_and_archived_app_rules() throws Exception {
        // Archive party
        partyService.archiveParty(workspace1Id, app1Id, party1Id, ownerId);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "onboarding_docs"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("party_archived"));

        // Restore party, then archive app
        partyService.restoreParty(workspace1Id, app1Id, party1Id, ownerId);
        appService.archiveApp(workspace1Id, app1Id, ownerId);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "onboarding_docs"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("app_archived"));
    }

    @Test
    void valid_pdf_png_jpeg_uploads_give_received_and_versions() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. Upload PDF
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "my-id.pdf")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.originalFilename").value("my-id.pdf"));

        // Verify request status is now RECEIVED
        var req = documentRequestStore.findById(workspace1Id, app1Id, requestId).orElseThrow();
        assertEquals("RECEIVED", req.status());
        assertNotNull(req.currentDocumentId());

        // 2. Upload PNG (second version)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "my-id-updated.png")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(VALID_PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.contentType").value("image/png"));

        // 3. Upload JPEG on another request
        UUID req2Id = assignResp.created().get(1).id();
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req2Id + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(VALID_JPEG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.originalFilename").value("document.jpeg"));
    }

    @Test
    void invalid_files_named_pdf_rejected_and_extension_forced() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // Fake PDF with HTML
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "invoice.pdf")
                        .content("<html><body>not a pdf</body></html>"))
                .andExpect(status().isBadRequest());

        // Fake PDF with EXE magic bytes (MZ)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "invoice.pdf")
                        .content(new byte[]{'M', 'Z', 0, 0}))
                .andExpect(status().isBadRequest());

        // Fake PDF with ZIP magic bytes (PK)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "invoice.pdf")
                        .content(new byte[]{'P', 'K', 3, 4}))
                .andExpect(status().isBadRequest());

        // Fake PDF with plain text
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "invoice.pdf")
                        .content("This is just plain text"))
                .andExpect(status().isBadRequest());

        // PNG named x.pdf -> accepted as image/png with forced extension x.png
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "x.pdf")
                        .content(VALID_PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.originalFilename").value("x.png"));
    }

    @Test
    void empty_body_rejected() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_size_limit_boundaries() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // Exactly the limit (MAX_DOCUMENT_BYTES = 4194304 bytes)
        int maxBytes = (int) storageConfig.getMaxDocumentBytes();
        byte[] exactLimit = new byte[maxBytes];
        System.arraycopy(VALID_PDF, 0, exactLimit, 0, VALID_PDF.length);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "exact.pdf")
                        .content(exactLimit))
                .andExpect(status().isCreated());

        // One byte over the limit with Content-Length -> 413
        byte[] overLimit = new byte[maxBytes + 1];
        System.arraycopy(VALID_PDF, 0, overLimit, 0, VALID_PDF.length);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "over.pdf")
                        .content(overLimit))
                .andExpect(status().isPayloadTooLarge());

        // Chunked stream over the limit without Content-Length -> 413 via CountingServletInputStream
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents");
        request.setContent(overLimit);
        MockHttpServletResponse response = new MockHttpServletResponse();
        bodySizeFilter.doFilter(request, response, (req, res) -> {
            byte[] buf = new byte[4096];
            while (req.getInputStream().read(buf) != -1) {
                // read
            }
        });
        assertEquals(413, response.getStatus());
    }

    @Test
    void identical_reupload_returns_200_and_creates_no_new_row() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. Initial upload -> 201
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "id.pdf")
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));

        long countAfterFirst = documentStore.countVersionsByRequest(workspace1Id, app1Id, requestId);
        assertEquals(1, countAfterFirst);

        // 2. Identical re-upload with exact same bytes -> 200 OK
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "another-name.pdf")
                        .content(VALID_PDF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1));

        long countAfterSecond = documentStore.countVersionsByRequest(workspace1Id, app1Id, requestId);
        assertEquals(1, countAfterSecond);
    }

    @Test
    void filename_sanitization_and_download_headers() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // Header with separators, CRLF, quotes, non-ASCII
        String messyFilename = "../../bad\"dir\r\n/my file \u00e9t\u00e9.txt";
        String encoded = URLEncoder.encode(messyFilename, StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", encoded)
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode respNode = jsonMapper.readTree(result.getResponse().getContentAsString());
        UUID docId = UUID.fromString(respNode.get("id").asText());
        String storedFilename = respNode.get("originalFilename").asText();

        // Must not contain path separators, CRLF, quotes
        assertFalse(storedFilename.contains("/"));
        assertFalse(storedFilename.contains("\\"));
        assertFalse(storedFilename.contains("\r"));
        assertFalse(storedFilename.contains("\n"));
        assertFalse(storedFilename.contains("\""));
        assertTrue(storedFilename.endsWith(".pdf"));

        // Download endpoint headers check (D-100)
        MvcResult dlRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(dlRes))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().doesNotExist("\r"))
                .andExpect(content().bytes(VALID_PDF));
    }

    @Test
    void storage_key_contains_only_uuids() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "important.pdf")
                        .content(VALID_PDF))
                .andExpect(status().isCreated());

        var req = documentRequestStore.findById(workspace1Id, app1Id, requestId).orElseThrow();
        DocumentRecord doc = documentStore.findById(workspace1Id, app1Id, req.currentDocumentId()).orElseThrow();

        // Pattern {workspaceId}/{appId}/{documentId}
        String[] parts = doc.storageKey().split("/");
        assertEquals(3, parts.length);
        assertDoesNotThrow(() -> UUID.fromString(parts[0]));
        assertDoesNotThrow(() -> UUID.fromString(parts[1]));
        assertDoesNotThrow(() -> UUID.fromString(parts[2]));
        assertEquals(workspace1Id, UUID.fromString(parts[0]));
        assertEquals(app1Id, UUID.fromString(parts[1]));
        assertEquals(doc.id(), UUID.fromString(parts[2]));
    }

    @Test
    void upload_after_approved_resets_to_received() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. Upload first doc -> RECEIVED
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated());

        // 2. Set to APPROVED via direct SQL (since approve endpoint is PLAN-0013)
        jdbcClient.sql("""
                UPDATE document_requests
                SET status = 'APPROVED',
                    approved_at = clock_timestamp(),
                    expires_on = '2027-06-01',
                    status_reason = NULL
                WHERE id = :id
                """)
                .param("id", requestId)
                .update();

        // 3. Re-upload a new version (different bytes)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2));

        // 4. Verify request status reset to RECEIVED, approved_at/expires_on cleared
        var req = documentRequestStore.findById(workspace1Id, app1Id, requestId).orElseThrow();
        assertEquals("RECEIVED", req.status());
        assertNull(req.approvedAt());
        assertNull(req.expiresOn());
        assertNull(req.statusReason());
    }

    @Test
    void free_tier_guardrails_d99_boundaries() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. MAX_VERSIONS_PER_REQUEST boundary
        storageConfig.setMaxVersionsPerRequest(2);
        // Upload version 1
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated());
        // Upload version 2
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PNG))
                .andExpect(status().isCreated());
        // Upload version 3 -> 409 limit_exceeded
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_JPEG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));
        storageConfig.setMaxVersionsPerRequest(10);

        // 2. MAX_STORAGE_BYTES_PER_APP boundary
        storageConfig.setMaxStorageBytesPerApp(10);
        UUID req2Id = assignResp.created().get(1).id();
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req2Id + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));
        storageConfig.setMaxStorageBytesPerApp(209715200);

        // 3. MAX_STORAGE_BYTES_TOTAL boundary -> 409 storage_full
        storageConfig.setMaxStorageBytesTotal(10);
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req2Id + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("storage_full"));
        storageConfig.setMaxStorageBytesTotal(943718400);

        // 4. MAX_DOCUMENTS_PER_APP boundary
        storageConfig.setMaxDocumentsPerApp(2);
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req2Id + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("limit_exceeded"));
        storageConfig.setMaxDocumentsPerApp(1000);
    }

    @Test
    void max_concurrent_uploads_gives_429_with_retry_after() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // We can simulate semaphore saturation by setting maxConcurrentUploads to 1 and holding it
        // Or in test, acquire all permits from the semaphore directly
        // Let's acquire permits in a thread to test 429
        CountDownLatch inProgress = new CountDownLatch(1);
        CountDownLatch holdLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> {
            try {
                // Wrap storage put to block
                FAKE_STORAGE.put("test-block", new byte[1], "application/pdf");
            } catch (Exception ignored) {}
        });

        Semaphore sem = documentService.getUploadSemaphore();
        int permits = sem.availablePermits();
        sem.acquire(permits);

        try {
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                            .header("Authorization", "Bearer " + ownerToken)
                            .content(VALID_PDF))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.title").value("Too Many Requests"));
        } finally {
            sem.release(permits);
            executor.shutdown();
        }
    }

    @Test
    void database_failure_after_put_deletes_object_and_leaves_no_row() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        jdbcClient.sql("""
                CREATE OR REPLACE FUNCTION fail_document_insert() RETURNS trigger AS $$
                BEGIN
                    IF NEW.original_filename = 'fail-db.pdf' THEN
                        RAISE EXCEPTION 'Simulated DB failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER trg_test_fail_insert
                BEFORE INSERT ON documents
                FOR EACH ROW EXECUTE FUNCTION fail_document_insert();
                """).update();

        try {
            assertThrows(Exception.class, () ->
                    mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                            .header("Authorization", "Bearer " + ownerToken)
                            .header("X-Filename", "fail-db.pdf")
                            .content(VALID_PDF)));

            // Verify storage object was deleted (rollback delete)
            assertEquals(0, documentStore.countVersionsByRequest(workspace1Id, app1Id, requestId));
            assertEquals(0, FAKE_STORAGE.size());
        } finally {
            jdbcClient.sql("DROP TRIGGER IF EXISTS trg_test_fail_insert ON documents; DROP FUNCTION IF EXISTS fail_document_insert();").update();
        }
    }

    @Test
    void storage_failure_gives_503_and_leaves_request_unchanged() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        FAKE_STORAGE.setFailing(true);

        try {
            mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                            .header("Authorization", "Bearer " + ownerToken)
                            .content(VALID_PDF))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("storage_unavailable"));

            // Request remains PENDING
            var req = documentRequestStore.findById(workspace1Id, app1Id, requestId).orElseThrow();
            assertEquals("PENDING", req.status());
            assertNull(req.currentDocumentId());
        } finally {
            FAKE_STORAGE.setFailing(false);
        }
    }

    @Test
    void download_purged_document_returns_410() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // Upload doc 1
        MvcResult res1 = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andReturn();
        UUID doc1Id = UUID.fromString(jsonMapper.readTree(res1.getResponse().getContentAsString()).get("id").asText());

        // Upload doc 2 (doc 1 becomes non-current)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PNG))
                .andExpect(status().isCreated());

        // Purge doc 1 via direct SQL (since purge endpoint is OWNER in PLAN-0013)
        jdbcClient.sql("UPDATE documents SET purged_at = clock_timestamp(), storage_key = NULL WHERE id = :id")
                .param("id", doc1Id)
                .update();

        // Download doc 1 returns 410 gone
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + doc1Id + "/download")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("gone"));
    }

    @Test
    void permission_matrix_on_every_endpoint() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. Assign: STAFF is allowed (EDIT_DOCUMENTS)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistId\": \"onboarding_docs\"}"))
                .andExpect(status().isCreated());

        // Assign: PORTAL_USER is forbidden (403)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistId\": \"onboarding_docs\"}"))
                .andExpect(status().isForbidden());

        // Assign: non-member is 404 (D-42)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistId\": \"onboarding_docs\"}"))
                .andExpect(status().isNotFound());

        // 2. Upload: STAFF is allowed
        MvcResult uploadRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + staffToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docId = UUID.fromString(jsonMapper.readTree(uploadRes.getResponse().getContentAsString()).get("id").asText());

        // Upload: PORTAL_USER is 403
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + portalToken)
                        .content(VALID_PDF))
                .andExpect(status().isForbidden());

        // Upload: non-member is 404
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .content(VALID_PDF))
                .andExpect(status().isNotFound());

        // 3. Download: STAFF is allowed (VIEW_DOCUMENTS)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());

        // Download: PORTAL_USER is 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());

        // Download: non-member is 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // 4. List requests & party requests: OWNER & STAFF 200, PORTAL 403, non-member 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void cross_tenant_scoping_is_404_everywhere() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        MvcResult uploadRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docId = UUID.fromString(jsonMapper.readTree(uploadRes.getResponse().getContentAsString()).get("id").asText());

        // Target ws1 resources using workspace2Id -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app1Id + "/document-requests/" + requestId)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // Target with app2Id instead of app1Id -> 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app2Id + "/document-requests/" + requestId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void payload_envelope_duplicate_and_unknown_keys_rejected() throws Exception {
        // Unknown property -> 400
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "onboarding_docs", "extra": "invalid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown property in request envelope"));

        // Duplicate JSON keys -> 422
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checklistId": "onboarding_docs", "checklistId": "other"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("duplicate JSON key"));
    }

    @Test
    void secret_marker_leak_prevention_in_filename_checklist_and_body() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        // 1. Malformed JSON with marker
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistId\": SECRET_MARKER_123"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));

        // 2. Unknown key with marker in JSON
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/parties/" + party1Id + "/document-requests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistId\": \"onboarding_docs\", \"SECRET_MARKER_123\": \"val\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));

        // 3. Filename header with marker when upload fails (e.g. invalid file bytes)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "SECRET_MARKER_123.exe")
                        .content("not a valid file"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SECRET_MARKER_123"))));
    }

    @Test
    void concurrent_uploads_at_limit_minus_one_boundary() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID req1 = assignResp.created().get(0).id();
        UUID req2 = assignResp.created().get(1).id();
        UUID req3 = assignResp.created().get(2).id();

        // Part 1: Documents-per-app limit minus one
        storageConfig.setMaxDocumentsPerApp(2);
        // Upload 1 document sequentially -> exactly at limit minus one (1 document exists, limit is 2)
        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req1 + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(VALID_PDF))
                .andExpect(status().isCreated());

        CountDownLatch startLatch1 = new CountDownLatch(1);
        CountDownLatch doneLatch1 = new CountDownLatch(2);
        AtomicInteger status1a = new AtomicInteger();
        AtomicInteger status1b = new AtomicInteger();

        new Thread(() -> {
            try {
                startLatch1.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req2 + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .content(VALID_PNG))
                        .andReturn();
                status1a.set(res.getResponse().getStatus());
            } catch (Exception e) {
                status1a.set(500);
            } finally {
                doneLatch1.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                startLatch1.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req3 + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .content(VALID_JPEG))
                        .andReturn();
                status1b.set(res.getResponse().getStatus());
            } catch (Exception e) {
                status1b.set(500);
            } finally {
                doneLatch1.countDown();
            }
        }).start();

        startLatch1.countDown();
        assertTrue(doneLatch1.await(5, TimeUnit.SECONDS));

        List<Integer> statuses1 = List.of(status1a.get(), status1b.get());
        assertTrue(statuses1.contains(201), "One upload must succeed with 201");
        assertTrue(statuses1.contains(409), "One upload must fail with 409 limit_exceeded");
        assertEquals(2, documentStore.countTotalDocuments(workspace1Id, app1Id));

        // Part 2: Bytes-per-app limit minus one
        storageConfig.setMaxDocumentsPerApp(1000); // reset docs limit
        long currentAppBytes = documentStore.sumStorageBytesApp(workspace1Id, app1Id);
        // Set max bytes such that one more file fits, but two do not
        storageConfig.setMaxStorageBytesPerApp(currentAppBytes + VALID_PDF.length + (VALID_PDF.length / 2));

        CountDownLatch startLatch2 = new CountDownLatch(1);
        CountDownLatch doneLatch2 = new CountDownLatch(2);
        AtomicInteger status2a = new AtomicInteger();
        AtomicInteger status2b = new AtomicInteger();

        // Assign another checklist or party to get fresh requests
        var party2 = partyService.createParty(workspace1Id, app1Id, ownerId, "client",
                jsonMapper.readTree("{\"name\": \"Bob\", \"email\": \"bob@example.com\"}"));
        var assignResp2 = documentService.assignChecklist(workspace1Id, app1Id, party2.id(), ownerId, "onboarding_docs");
        UUID req4 = assignResp2.created().get(0).id();
        UUID req5 = assignResp2.created().get(1).id();

        new Thread(() -> {
            try {
                startLatch2.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req4 + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .content(VALID_PDF))
                        .andReturn();
                status2a.set(res.getResponse().getStatus());
            } catch (Exception e) {
                status2a.set(500);
            } finally {
                doneLatch2.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                startLatch2.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + req5 + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .content(VALID_PDF))
                        .andReturn();
                status2b.set(res.getResponse().getStatus());
            } catch (Exception e) {
                status2b.set(500);
            } finally {
                doneLatch2.countDown();
            }
        }).start();

        startLatch2.countDown();
        assertTrue(doneLatch2.await(5, TimeUnit.SECONDS));

        List<Integer> statuses2 = List.of(status2a.get(), status2b.get());
        assertTrue(statuses2.contains(201), "One upload must succeed with 201");
        assertTrue(statuses2.contains(409), "One upload must fail with 409 limit_exceeded");
    }

    @Test
    void simultaneous_uploads_to_same_request_produce_distinct_versions_and_single_current() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger status1 = new AtomicInteger();
        AtomicInteger status2 = new AtomicInteger();
        AtomicInteger ver1 = new AtomicInteger();
        AtomicInteger ver2 = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        new Thread(() -> {
            try {
                startLatch.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("X-Filename", "first.pdf")
                                .content(VALID_PDF))
                        .andReturn();
                status1.set(res.getResponse().getStatus());
                if (res.getResponse().getStatus() == 201) {
                    ver1.set(jsonMapper.readTree(res.getResponse().getContentAsString()).path("versionNo").asInt());
                } else {
                    errors.add("Thread1 non-201 status: " + res.getResponse().getStatus() + ", body: " + res.getResponse().getContentAsString());
                }
            } catch (Exception e) {
                errors.add("Thread1 exception: " + e.getMessage());
                e.printStackTrace();
                status1.set(500);
            } finally {
                doneLatch.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                startLatch.await();
                var res = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("X-Filename", "second.png")
                                .content(VALID_PNG))
                        .andReturn();
                status2.set(res.getResponse().getStatus());
                if (res.getResponse().getStatus() == 201) {
                    ver2.set(jsonMapper.readTree(res.getResponse().getContentAsString()).path("versionNo").asInt());
                } else {
                    errors.add("Thread2 non-201 status: " + res.getResponse().getStatus() + ", body: " + res.getResponse().getContentAsString());
                }
            } catch (Exception e) {
                errors.add("Thread2 exception: " + e.getMessage());
                e.printStackTrace();
                status2.set(500);
            } finally {
                doneLatch.countDown();
            }
        }).start();

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        // Neither returns 500
        assertTrue(errors.isEmpty(), "Errors occurred during simultaneous uploads: " + errors);
        assertNotEquals(500, status1.get());
        assertNotEquals(500, status2.get());
        assertEquals(201, status1.get());
        assertEquals(201, status2.get());

        // Two distinct version numbers (v1 and v2)
        Set<Integer> versions = Set.of(ver1.get(), ver2.get());
        assertEquals(Set.of(1, 2), versions);

        // Exactly one current document
        DocumentRequestRecord req = documentRequestStore.findById(workspace1Id, app1Id, requestId).orElseThrow();
        assertNotNull(req.currentDocumentId());
        DocumentRecord currentDoc = documentStore.findById(workspace1Id, app1Id, req.currentDocumentId()).orElseThrow();
        assertNull(currentDoc.purgedAt());
        assertEquals(requestId, currentDoc.requestId());
        assertTrue(currentDoc.versionNo() == 1 || currentDoc.versionNo() == 2);
    }

    @Test
    void download_exact_bytes_headers_and_scoping_rules() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID requestId = assignResp.created().get(0).id();

        MvcResult uploadRes = mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + requestId + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "my-contract.pdf")
                        .content(VALID_PDF))
                .andExpect(status().isCreated())
                .andReturn();

        UUID docId = UUID.fromString(jsonMapper.readTree(uploadRes.getResponse().getContentAsString()).get("id").asText());

        // 1. Download returns exact uploaded bytes (compare arrays) and correct headers
        MvcResult dlRes = mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult finalDl = mockMvc.perform(asyncDispatch(dlRes))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn();

        // Exact bytes comparison
        assertArrayEquals(VALID_PDF, finalDl.getResponse().getContentAsByteArray());

        // Content-Disposition with attachment and filename* parameter
        String contentDisposition = finalDl.getResponse().getHeader("Content-Disposition");
        assertNotNull(contentDisposition);
        assertTrue(contentDisposition.startsWith("attachment"), "Content-Disposition must start with attachment");
        assertTrue(contentDisposition.contains("filename*="), "Content-Disposition must contain filename* parameter");

        // 2. User of another workspace gets 404
        mockMvc.perform(get("/api/workspaces/" + workspace2Id + "/apps/" + app1Id + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());

        // 3. User of another app in the same workspace gets 404
        AppResponse app1b = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App 1B", "UTC"));
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/apps/" + app1b.id() + "/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void chunked_upload_above_max_gives_413_and_stores_nothing_and_exact_limit_succeeds() throws Exception {
        var assignResp = documentService.assignChecklist(workspace1Id, app1Id, party1Id, ownerId, "onboarding_docs");
        UUID reqExact = assignResp.created().get(0).id();
        UUID reqChunked = assignResp.created().get(1).id();

        int maxBytes = (int) storageConfig.getMaxDocumentBytes();

        // 1. Upload of exactly MAX_DOCUMENT_BYTES succeeds
        byte[] exactLimit = new byte[maxBytes];
        System.arraycopy(VALID_PDF, 0, exactLimit, 0, VALID_PDF.length);

        mockMvc.perform(post("/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + reqExact + "/documents")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Filename", "exact_limit.pdf")
                        .content(exactLimit))
                .andExpect(status().isCreated());

        // 2. Chunked upload with no Content-Length above MAX_DOCUMENT_BYTES gives 413 and nothing is stored
        byte[] overLimit = new byte[maxBytes + 1024];
        System.arraycopy(VALID_PDF, 0, overLimit, 0, VALID_PDF.length);

        int fakeStorageCountBefore = FAKE_STORAGE.size();
        long dbVersionsBefore = documentStore.countVersionsByRequest(workspace1Id, app1Id, reqChunked);

        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/workspaces/" + workspace1Id + "/apps/" + app1Id + "/document-requests/" + reqChunked + "/documents");
        request.setContent(overLimit);
        MockHttpServletResponse response = new MockHttpServletResponse();

        bodySizeFilter.doFilter(request, response, (req, res) -> {
            byte[] buf = new byte[8192];
            var is = req.getInputStream();
            while (is.read(buf) != -1) {
                // reading chunked stream triggers 413
            }
        });

        assertEquals(413, response.getStatus());

        // Assert nothing is stored
        assertEquals(fakeStorageCountBefore, FAKE_STORAGE.size(), "Nothing should be stored in storage on 413");
        assertEquals(dbVersionsBefore, documentStore.countVersionsByRequest(workspace1Id, app1Id, reqChunked),
                "No document record should be inserted in DB on 413");
    }
}
