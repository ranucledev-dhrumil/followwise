package com.followwise.document;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentStore {

    private final JdbcClient jdbcClient;

    private static final RowMapper<DocumentRecord> DOCUMENT_ROW_MAPPER = (rs, rowNum) -> new DocumentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getInt("version_no"),
            rs.getString("storage_key"),
            rs.getString("original_filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getObject("uploaded_by", UUID.class),
            toInstant(rs.getTimestamp("uploaded_at")),
            toInstant(rs.getTimestamp("purged_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public DocumentStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(UUID id, UUID workspaceId, UUID appId, UUID partyId, UUID requestId,
                       int versionNo, String storageKey, String originalFilename,
                       String contentType, long sizeBytes, String sha256,
                       UUID uploadedBy, Instant now) {
        jdbcClient.sql("""
                INSERT INTO documents (
                    id, workspace_id, app_id, party_id, request_id, version_no,
                    storage_key, original_filename, content_type, size_bytes, sha256,
                    uploaded_by, uploaded_at, purged_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :rid, :versionNo,
                    :storageKey, :filename, :contentType, :sizeBytes, :sha256,
                    :uploadedBy, :uploadedAt, NULL
                )
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("rid", requestId)
                .param("versionNo", versionNo)
                .param("storageKey", storageKey)
                .param("filename", originalFilename)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("sha256", sha256)
                .param("uploadedBy", uploadedBy)
                .param("uploadedAt", Timestamp.from(now))
                .update();
    }

    public Optional<DocumentRecord> findById(UUID workspaceId, UUID appId, UUID documentId) {
        return jdbcClient.sql("""
                SELECT * FROM documents
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", documentId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(DOCUMENT_ROW_MAPPER)
                .optional();
    }

    public List<DocumentRecord> findVersionsByRequest(UUID workspaceId, UUID appId, UUID requestId) {
        return jdbcClient.sql("""
                SELECT * FROM documents
                WHERE workspace_id = :wid AND app_id = :aid AND request_id = :rid
                ORDER BY version_no ASC
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("rid", requestId)
                .query(DOCUMENT_ROW_MAPPER)
                .list();
    }

    public int findLatestVersionNo(UUID workspaceId, UUID appId, UUID requestId) {
        Integer max = jdbcClient.sql("""
                SELECT COALESCE(MAX(version_no), 0)
                FROM documents
                WHERE workspace_id = :wid AND app_id = :aid AND request_id = :rid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("rid", requestId)
                .query(Integer.class)
                .single();
        return max != null ? max : 0;
    }

    public long countTotalDocuments(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM documents
                WHERE workspace_id = :wid AND app_id = :aid AND purged_at IS NULL
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public long sumStorageBytesApp(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(size_bytes), 0)::bigint
                FROM documents
                WHERE workspace_id = :wid AND app_id = :aid AND purged_at IS NULL
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public long sumStorageBytesTotal() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(size_bytes), 0)::bigint
                FROM documents
                WHERE purged_at IS NULL
                """)
                .query(Long.class)
                .single();
    }

    public long countVersionsByRequest(UUID workspaceId, UUID appId, UUID requestId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM documents
                WHERE workspace_id = :wid AND app_id = :aid AND request_id = :rid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("rid", requestId)
                .query(Long.class)
                .single();
    }

    public int purge(UUID workspaceId, UUID appId, UUID documentId, Instant now) {
        return jdbcClient.sql("""
                UPDATE documents
                SET purged_at = :now,
                    storage_key = NULL
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND purged_at IS NULL
                """)
                .param("id", documentId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
