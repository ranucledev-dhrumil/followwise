package com.followwise.document;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRequestStore {

    private final JdbcClient jdbcClient;

    private static final RowMapper<DocumentRequestRecord> REQUEST_ROW_MAPPER = (rs, rowNum) -> new DocumentRequestRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getString("checklist_id"),
            rs.getString("item_id"),
            rs.getString("label"),
            rs.getBoolean("required"),
            rs.getObject("expiry_days", Integer.class),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("status_reason"),
            rs.getObject("current_document_id", UUID.class),
            toInstant(rs.getTimestamp("approved_at")),
            rs.getObject("expires_on", LocalDate.class),
            rs.getInt("version"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("updated_by", UUID.class),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("cancelled_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public DocumentRequestStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(UUID id, UUID workspaceId, UUID appId, UUID partyId,
                       String checklistId, String itemId, String label, boolean required,
                       Integer expiryDays, LocalDate dueDate, String status,
                       UUID createdBy, Instant now) {
        jdbcClient.sql("""
                INSERT INTO document_requests (
                    id, workspace_id, app_id, party_id, checklist_id, item_id,
                    label, required, expiry_days, due_date, status, status_reason,
                    current_document_id, approved_at, expires_on, version,
                    created_by, updated_by, created_at, updated_at, cancelled_at
                ) VALUES (
                    :id, :wid, :aid, :pid, :checklistId, :itemId,
                    :label, :required, :expiryDays, :dueDate, :status, NULL,
                    NULL, NULL, NULL, 1,
                    :createdBy, :createdBy, :now, :now, NULL
                )
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("checklistId", checklistId)
                .param("itemId", itemId)
                .param("label", label)
                .param("required", required)
                .param("expiryDays", expiryDays)
                .param("dueDate", dueDate)
                .param("status", status)
                .param("createdBy", createdBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public Optional<DocumentRequestRecord> findById(UUID workspaceId, UUID appId, UUID id) {
        return jdbcClient.sql("""
                SELECT * FROM document_requests
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(REQUEST_ROW_MAPPER)
                .optional();
    }

    public List<DocumentRequestRecord> findByParty(UUID workspaceId, UUID appId, UUID partyId) {
        return jdbcClient.sql("""
                SELECT * FROM document_requests
                WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                ORDER BY created_at ASC, id ASC
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .query(REQUEST_ROW_MAPPER)
                .list();
    }

    public List<DocumentRequestRecord> findPage(
            UUID workspaceId, UUID appId, UUID partyId,
            String status, Boolean overdue, Integer expiringWithinDays, LocalDate todayInApp,
            int page, int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM document_requests
                WHERE workspace_id = :wid AND app_id = :aid
                """);
        applyFilters(sql, partyId, status, overdue, expiringWithinDays);

        sql.append(" ORDER BY due_date ASC NULLS LAST, id ASC LIMIT :size OFFSET :offset");

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("size", size)
                .param("offset", (long) page * size);

        bindFilterParams(spec, partyId, status, overdue, expiringWithinDays, todayInApp);

        return spec.query(REQUEST_ROW_MAPPER).list();
    }

    public long countFiltered(
            UUID workspaceId, UUID appId, UUID partyId,
            String status, Boolean overdue, Integer expiringWithinDays, LocalDate todayInApp
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM document_requests
                WHERE workspace_id = :wid AND app_id = :aid
                """);
        applyFilters(sql, partyId, status, overdue, expiringWithinDays);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId);

        bindFilterParams(spec, partyId, status, overdue, expiringWithinDays, todayInApp);

        return spec.query(Long.class).single();
    }

    private void applyFilters(StringBuilder sql, UUID partyId, String status, Boolean overdue, Integer expiringWithinDays) {
        if (partyId != null) {
            sql.append(" AND party_id = :pid");
        }

        if (status == null || status.isBlank()) {
            sql.append(" AND cancelled_at IS NULL");
        } else if ("cancelled".equalsIgnoreCase(status)) {
            sql.append(" AND cancelled_at IS NOT NULL");
        } else if ("expired".equalsIgnoreCase(status)) {
            sql.append(" AND cancelled_at IS NULL AND status = 'APPROVED' AND expires_on IS NOT NULL AND expires_on < :todayInApp");
        } else {
            sql.append(" AND cancelled_at IS NULL AND status = :statusFilter");
        }

        if (overdue != null) {
            if (overdue) {
                sql.append(" AND (required = true AND status IN ('PENDING', 'REJECTED') AND due_date IS NOT NULL AND due_date < :todayInApp)");
            } else {
                sql.append(" AND NOT (required = true AND status IN ('PENDING', 'REJECTED') AND due_date IS NOT NULL AND due_date < :todayInApp)");
            }
        }

        if (expiringWithinDays != null) {
            sql.append(" AND (status = 'APPROVED' AND expires_on IS NOT NULL AND expires_on >= :todayInApp AND expires_on <= :expiringEnd)");
        }
    }

    private void bindFilterParams(
            org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec,
            UUID partyId, String status, Boolean overdue, Integer expiringWithinDays, LocalDate todayInApp
    ) {
        if (partyId != null) {
            spec.param("pid", partyId);
        }

        boolean needsToday = false;
        if (status != null && !status.isBlank()) {
            if ("expired".equalsIgnoreCase(status)) {
                needsToday = true;
            } else if (!"cancelled".equalsIgnoreCase(status)) {
                spec.param("statusFilter", status.toUpperCase());
            }
        }

        if (overdue != null) {
            needsToday = true;
        }

        if (expiringWithinDays != null) {
            needsToday = true;
            spec.param("expiringEnd", todayInApp.plusDays(expiringWithinDays));
        }

        if (needsToday && todayInApp != null) {
            spec.param("todayInApp", todayInApp);
        }
    }

    public long countActiveByParty(UUID workspaceId, UUID appId, UUID partyId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM document_requests
                WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                  AND cancelled_at IS NULL
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .query(Long.class)
                .single();
    }

    public boolean existsActiveItem(UUID workspaceId, UUID appId, UUID partyId, String checklistId, String itemId) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM document_requests
                    WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                      AND checklist_id = :cid AND item_id = :iid
                      AND cancelled_at IS NULL
                )
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("cid", checklistId)
                .param("iid", itemId)
                .query(Boolean.class)
                .single();
    }

    public Optional<UUID> lockRequestRow(UUID workspaceId, UUID appId, UUID id) {
        return jdbcClient.sql("""
                SELECT id FROM document_requests
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                FOR UPDATE
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(UUID.class)
                .optional();
    }

    public int updateToReceived(UUID workspaceId, UUID appId, UUID requestId, UUID documentId,
                                int expectedVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE document_requests
                SET status = 'RECEIVED',
                    current_document_id = :docId,
                    status_reason = NULL,
                    approved_at = NULL,
                    expires_on = NULL,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion
                  AND cancelled_at IS NULL
                """)
                .param("id", requestId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("docId", documentId)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public int updateToApproved(UUID workspaceId, UUID appId, UUID requestId,
                                LocalDate expiresOn, int expectedVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE document_requests
                SET status = 'APPROVED',
                    approved_at = :now,
                    expires_on = :expiresOn,
                    status_reason = NULL,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :now
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion
                  AND cancelled_at IS NULL
                """)
                .param("id", requestId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("expiresOn", expiresOn)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int updateToRejected(UUID workspaceId, UUID appId, UUID requestId,
                                String reason, int expectedVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE document_requests
                SET status = 'REJECTED',
                    status_reason = :reason,
                    approved_at = NULL,
                    expires_on = NULL,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :now
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion
                  AND cancelled_at IS NULL
                """)
                .param("id", requestId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("reason", reason)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int updateDueDate(UUID workspaceId, UUID appId, UUID requestId,
                             LocalDate dueDate, int expectedVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE document_requests
                SET due_date = :dueDate,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :now
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion
                  AND cancelled_at IS NULL
                """)
                .param("id", requestId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("dueDate", dueDate)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int updateToCancelled(UUID workspaceId, UUID appId, UUID requestId,
                                 int expectedVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE document_requests
                SET cancelled_at = :now,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :now
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion
                  AND cancelled_at IS NULL
                """)
                .param("id", requestId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("now", Timestamp.from(now))
                .update();
    }
}
