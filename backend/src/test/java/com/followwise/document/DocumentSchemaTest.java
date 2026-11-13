package com.followwise.document;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import com.followwise.app.AppPayloadParser;
import com.followwise.app.AppResponse;
import com.followwise.app.AppService;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.party.PartyService;
import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Role;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private AppService appService;

    @Autowired
    private PartyService partyService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private UUID workspace1Id;
    private UUID workspace2Id;
    private UUID app1Id;
    private UUID app2Id;
    private UUID party1Id;
    private UUID party2Id;
    private UUID ownerId;

    @BeforeEach
    void setUp() throws Exception {
        TestDbHelper.cleanDatabase(dataSource);

        Instant now = Instant.now();
        ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));

        Workspace ws1 = new Workspace(UUID.randomUUID(), "Workspace 1", now);
        Workspace ws2 = new Workspace(UUID.randomUUID(), "Workspace 2", now);
        workspaceRepository.save(ws1);
        workspaceRepository.save(ws2);
        workspace1Id = ws1.getId();
        workspace2Id = ws2.getId();

        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace1Id, Role.OWNER, now));
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace2Id, Role.OWNER, now));

        AppResponse app1 = appService.createApp(workspace1Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App", "UTC"));
        app1Id = app1.id();

        AppResponse app2 = appService.createApp(workspace2Id, ownerId,
                new AppPayloadParser.ParsedCreateAppRequest("client-document-tracker", "CDT App 2", "UTC"));
        app2Id = app2.id();

        var p1 = partyService.createParty(workspace1Id, app1Id, ownerId, "client",
                jsonMapper.readTree("""
                        {"name": "Client 1", "email": "client1@example.com"}
                        """));
        party1Id = p1.id();

        var p2 = partyService.createParty(workspace1Id, app1Id, ownerId, "client",
                jsonMapper.readTree("""
                        {"name": "Client 2", "email": "client2@example.com"}
                        """));
        party2Id = p2.id();
    }

    private UUID insertRequest(UUID requestId, UUID partyId, String status, String statusReason, LocalDate expiresOn) {
        jdbcClient.sql("""
                INSERT INTO document_requests (
                    id, workspace_id, app_id, party_id, checklist_id, item_id,
                    label, required, status, status_reason, expires_on,
                    created_by, updated_by
                ) VALUES (
                    :id, :wid, :aid, :pid, 'onboarding_docs', 'gov_id',
                    'Government Photo ID', true, :status, :statusReason, :expiresOn,
                    :userId, :userId
                )
                """)
                .param("id", requestId)
                .param("wid", workspace1Id)
                .param("aid", app1Id)
                .param("pid", partyId)
                .param("status", status)
                .param("statusReason", statusReason)
                .param("expiresOn", expiresOn)
                .param("userId", ownerId)
                .update();
        return requestId;
    }

    private UUID insertDocument(UUID docId, UUID requestId, UUID partyId, int versionNo,
                                String storageKey, String contentType, long sizeBytes, Instant purgedAt) {
        String sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        jdbcClient.sql("""
                INSERT INTO documents (
                    id, workspace_id, app_id, party_id, request_id, version_no,
                    storage_key, original_filename, content_type, size_bytes, sha256,
                    uploaded_by, purged_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :rid, :versionNo,
                    :storageKey, 'id.pdf', :contentType, :sizeBytes, :sha256,
                    :userId, :purgedAt
                )
                """)
                .param("id", docId)
                .param("wid", workspace1Id)
                .param("aid", app1Id)
                .param("pid", partyId)
                .param("rid", requestId)
                .param("versionNo", versionNo)
                .param("storageKey", storageKey)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("sha256", sha256)
                .param("userId", ownerId)
                .param("purgedAt", purgedAt)
                .update();
        return docId;
    }

    @Test
    void direct_sql_bad_content_type_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "text/plain", 100, null));
    }

    @Test
    void direct_sql_size_0_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 0, null));
    }

    @Test
    void direct_sql_size_above_52428800_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 52428801, null));
    }

    @Test
    void direct_sql_rejected_without_reason_fails() {
        assertThrows(DataAccessException.class, () ->
                insertRequest(UUID.randomUUID(), party1Id, "REJECTED", null, null));
    }

    @Test
    void direct_sql_expires_on_without_approved_fails() {
        assertThrows(DataAccessException.class, () ->
                insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, LocalDate.now().plusDays(30)));
        assertThrows(DataAccessException.class, () ->
                insertRequest(UUID.randomUUID(), party1Id, "REJECTED", "blurry", LocalDate.now().plusDays(30)));
    }

    @Test
    void direct_sql_storage_key_null_with_purged_at_null_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party1Id, 1, null, "application/pdf", 100, null));
    }

    @Test
    void direct_sql_storage_key_not_null_with_purged_at_not_null_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 100, Instant.now()));
    }

    @Test
    void direct_sql_document_pointing_at_another_partys_or_apps_request_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        // Pointing document to party2 when request belongs to party1 fails composite FK
        assertThrows(DataAccessException.class, () ->
                insertDocument(UUID.randomUUID(), reqId, party2Id, 1, "k1", "application/pdf", 100, null));
    }

    @Test
    void direct_sql_second_current_document_fails() {
        UUID req1Id = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        UUID req2Id = UUID.randomUUID();
        // Insert req2 with checklist_id item_id different to not hit unique checklist item
        jdbcClient.sql("""
                INSERT INTO document_requests (
                    id, workspace_id, app_id, party_id, checklist_id, item_id,
                    label, required, status, created_by, updated_by
                ) VALUES (
                    :id, :wid, :aid, :pid, 'onboarding_docs', 'tax_form',
                    'Tax Form', true, 'PENDING', :userId, :userId
                )
                """)
                .param("id", req2Id)
                .param("wid", workspace1Id)
                .param("aid", app1Id)
                .param("pid", party1Id)
                .param("userId", ownerId)
                .update();

        UUID doc1Id = insertDocument(UUID.randomUUID(), req1Id, party1Id, 1, "k1", "application/pdf", 100, null);

        // Set req1's current_document_id to doc1
        jdbcClient.sql("UPDATE document_requests SET current_document_id = :docId WHERE id = :reqId")
                .param("docId", doc1Id)
                .param("reqId", req1Id)
                .update();

        // Setting req2's current_document_id to doc1 (already current of req1) fails partial unique index
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE document_requests SET current_document_id = :docId WHERE id = :reqId")
                        .param("docId", doc1Id)
                        .param("reqId", req2Id)
                        .update());

        // Also, setting current_document_id of req2 to doc1 fails composite FK (doc1 request_id is req1, not req2)
        // Let's create a second document on req1 and try to set req2's current_document_id to it
        UUID doc2Id = insertDocument(UUID.randomUUID(), req1Id, party1Id, 2, "k2", "application/pdf", 200, null);
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE document_requests SET current_document_id = :docId WHERE id = :reqId")
                        .param("docId", doc2Id)
                        .param("reqId", req2Id)
                        .update());
    }

    @Test
    void direct_sql_update_immutable_columns_fails() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        UUID docId = insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 100, null);

        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE documents SET original_filename = 'other.pdf' WHERE id = :id")
                        .param("id", docId)
                        .update());

        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE documents SET size_bytes = 200 WHERE id = :id")
                        .param("id", docId)
                        .update());

        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE documents SET version_no = 2 WHERE id = :id")
                        .param("id", docId)
                        .update());
    }

    @Test
    void direct_sql_delete_and_truncate_fail() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        UUID docId = insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 100, null);

        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("DELETE FROM documents WHERE id = :id")
                        .param("id", docId)
                        .update());

        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("TRUNCATE documents")
                        .update());
    }

    @Test
    void direct_sql_purge_update_succeeds_and_cannot_purge_current_document() {
        UUID reqId = insertRequest(UUID.randomUUID(), party1Id, "PENDING", null, null);
        UUID doc1Id = insertDocument(UUID.randomUUID(), reqId, party1Id, 1, "k1", "application/pdf", 100, null);
        UUID doc2Id = insertDocument(UUID.randomUUID(), reqId, party1Id, 2, "k2", "application/pdf", 200, null);

        // Make doc2 the current document of reqId
        jdbcClient.sql("UPDATE document_requests SET current_document_id = :docId WHERE id = :reqId")
                .param("docId", doc2Id)
                .param("reqId", reqId)
                .update();

        // Trying to purge doc2 (which is the current document) fails via trigger check!
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE documents SET purged_at = clock_timestamp(), storage_key = NULL WHERE id = :id")
                        .param("id", doc2Id)
                        .update());

        // Purging doc1 (non-current) succeeds!
        int rows = jdbcClient.sql("UPDATE documents SET purged_at = clock_timestamp(), storage_key = NULL WHERE id = :id")
                .param("id", doc1Id)
                .update();
        assertEquals(1, rows);

        // Once purged, updating it again fails
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE documents SET purged_at = clock_timestamp() WHERE id = :id")
                        .param("id", doc1Id)
                        .update());
    }
}
