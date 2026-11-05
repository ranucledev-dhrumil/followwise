package com.followwise.party;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecordStore {

    private final JdbcClient jdbcClient;

    private static final RowMapper<RecordRow> RECORD_ROW_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getString("record_type"),
            rs.getString("display_name"),
            rs.getString("data"),
            rs.getInt("definition_version"),
            rs.getInt("version"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("updated_by", UUID.class),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("archived_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public RecordStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> lockApp(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT id FROM apps WHERE id = :aid AND workspace_id = :wid AND archived_at IS NULL FOR UPDATE")
                .param("aid", appId)
                .param("wid", workspaceId)
                .query(UUID.class)
                .optional();
    }

    public long countActive(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT count(*) FROM records WHERE workspace_id = :wid AND app_id = :aid AND archived_at IS NULL")
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public long countTotal(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT count(*) FROM records WHERE workspace_id = :wid AND app_id = :aid")
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public void insert(UUID id, UUID workspaceId, UUID appId, String recordType, String displayName,
                       String dataJson, int defVersion, UUID createdBy, Instant now) {
        jdbcClient.sql("""
                INSERT INTO records (
                    id, workspace_id, app_id, record_type, display_name, data,
                    definition_version, version, created_by, updated_by, created_at, updated_at, archived_at
                ) VALUES (
                    :id, :wid, :aid, :recordType, :displayName, CAST(:data AS jsonb),
                    :defVersion, 1, :createdBy, :createdBy, :createdAt, :updatedAt, NULL
                )
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("recordType", recordType)
                .param("displayName", displayName)
                .param("data", dataJson)
                .param("defVersion", defVersion)
                .param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public Optional<RecordRow> findById(UUID workspaceId, UUID appId, UUID id) {
        return jdbcClient.sql("""
                SELECT id, workspace_id, app_id, record_type, display_name, data::text AS data,
                       definition_version, version, created_by, updated_by, created_at, updated_at, archived_at
                FROM records
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(RECORD_ROW_MAPPER)
                .optional();
    }

    public List<RecordRow> findPage(UUID workspaceId, UUID appId, String recordType, String q,
                                    boolean includeArchived, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, workspace_id, app_id, record_type, display_name, data::text AS data,
                       definition_version, version, created_by, updated_by, created_at, updated_at, archived_at
                FROM records
                WHERE workspace_id = :wid AND app_id = :aid AND record_type = :recordType
                """);

        if (!includeArchived) {
            sql.append(" AND archived_at IS NULL");
        }

        boolean hasQ = q != null && !q.trim().isEmpty();
        if (hasQ) {
            sql.append(" AND lower(display_name) LIKE :pattern ESCAPE '\\'");
        }

        sql.append(" ORDER BY lower(display_name) ASC, id ASC LIMIT :size OFFSET :offset");

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("recordType", recordType)
                .param("size", size)
                .param("offset", (long) page * size);

        if (hasQ) {
            String escaped = escapeLike(q.trim());
            spec.param("pattern", escaped.toLowerCase(Locale.ROOT) + "%");
        }

        return spec.query(RECORD_ROW_MAPPER).list();
    }

    public long countFiltered(UUID workspaceId, UUID appId, String recordType, String q, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*)
                FROM records
                WHERE workspace_id = :wid AND app_id = :aid AND record_type = :recordType
                """);

        if (!includeArchived) {
            sql.append(" AND archived_at IS NULL");
        }

        boolean hasQ = q != null && !q.trim().isEmpty();
        if (hasQ) {
            sql.append(" AND lower(display_name) LIKE :pattern ESCAPE '\\'");
        }

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("recordType", recordType);

        if (hasQ) {
            String escaped = escapeLike(q.trim());
            spec.param("pattern", escaped.toLowerCase(Locale.ROOT) + "%");
        }

        return spec.query(Long.class).single();
    }

    public int update(UUID workspaceId, UUID appId, UUID id, int expectedVersion,
                      String displayName, String dataJson, int defVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE records
                SET display_name = :displayName,
                    data = CAST(:data AS jsonb),
                    definition_version = :defVersion,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                  AND version = :expectedVersion AND archived_at IS NULL
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("expectedVersion", expectedVersion)
                .param("displayName", displayName)
                .param("data", dataJson)
                .param("defVersion", defVersion)
                .param("updatedBy", updatedBy)
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public int archive(UUID workspaceId, UUID appId, UUID id, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE records
                SET archived_at = :archivedAt,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND archived_at IS NULL
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("archivedAt", Timestamp.from(now))
                .param("updatedBy", updatedBy)
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public int restore(UUID workspaceId, UUID appId, UUID id, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE records
                SET archived_at = NULL,
                    version = version + 1,
                    updated_by = :updatedBy,
                    updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND archived_at IS NOT NULL
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("updatedBy", updatedBy)
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public RefChecker.TargetStatus checkRef(UUID workspaceId, UUID appId, UUID targetId, String expectedType) {
        return jdbcClient.sql("""
                SELECT record_type, archived_at
                FROM records
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", targetId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query((rs, rowNum) -> {
                    String actualType = rs.getString("record_type");
                    boolean archived = rs.getTimestamp("archived_at") != null;
                    if (!actualType.equals(expectedType)) {
                        return RefChecker.TargetStatus.NOT_FOUND;
                    }
                    return archived ? RefChecker.TargetStatus.ARCHIVED : RefChecker.TargetStatus.VALID;
                })
                .optional()
                .orElse(RefChecker.TargetStatus.NOT_FOUND);
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
