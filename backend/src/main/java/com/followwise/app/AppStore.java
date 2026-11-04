package com.followwise.app;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AppStore {

    private final JdbcClient jdbcClient;

    private static final RowMapper<AppRecord> APP_ROW_MAPPER = (rs, rowNum) -> new AppRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getString("name"),
            rs.getString("template_key"),
            rs.getInt("current_version"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("archived_at"))
    );

    private static final RowMapper<AppDefinitionVersionRecord> VERSION_ROW_MAPPER = (rs, rowNum) -> new AppDefinitionVersionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getInt("version"),
            rs.getString("definition"),
            rs.getString("note"),
            rs.getObject("created_by", UUID.class),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<AppVersionSummaryRecord> VERSION_SUMMARY_ROW_MAPPER = (rs, rowNum) -> new AppVersionSummaryRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getInt("version"),
            rs.getString("note"),
            rs.getObject("created_by", UUID.class),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public AppStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockWorkspace(UUID workspaceId) {
        jdbcClient.sql("SELECT id FROM workspaces WHERE id = :wid FOR UPDATE")
                .param("wid", workspaceId)
                .query(UUID.class)
                .optional();
    }

    public long countActiveApps(UUID workspaceId) {
        return jdbcClient.sql("SELECT count(*) FROM apps WHERE workspace_id = :wid AND archived_at IS NULL")
                .param("wid", workspaceId)
                .query(Long.class)
                .single();
    }

    public long countTotalApps(UUID workspaceId) {
        return jdbcClient.sql("SELECT count(*) FROM apps WHERE workspace_id = :wid")
                .param("wid", workspaceId)
                .query(Long.class)
                .single();
    }

    public void insertApp(UUID id, UUID workspaceId, String name, String templateKey, int currentVersion, Instant now) {
        jdbcClient.sql("""
                INSERT INTO apps (id, workspace_id, name, template_key, current_version, created_at, updated_at)
                VALUES (:id, :workspaceId, :name, :templateKey, :currentVersion, :createdAt, :updatedAt)
                """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("name", name)
                .param("templateKey", templateKey)
                .param("currentVersion", currentVersion)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public void insertVersion(UUID id, UUID appId, int version, String definitionJson, String note, UUID createdBy, Instant now) {
        jdbcClient.sql("""
                INSERT INTO app_definition_versions (id, app_id, version, definition, note, created_by, created_at)
                VALUES (:id, :appId, :version, CAST(:definition AS jsonb), :note, :createdBy, :createdAt)
                """)
                .param("id", id)
                .param("appId", appId)
                .param("version", version)
                .param("definition", definitionJson)
                .param("note", note)
                .param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    public Optional<AppRecord> findAppByIdAndWorkspaceId(UUID appId, UUID workspaceId) {
        return jdbcClient.sql("""
                SELECT id, workspace_id, name, template_key, current_version, created_at, updated_at, archived_at
                FROM apps
                WHERE id = :aid AND workspace_id = :wid
                """)
                .param("aid", appId)
                .param("wid", workspaceId)
                .query(APP_ROW_MAPPER)
                .optional();
    }

    public List<AppRecord> findAppsByWorkspaceId(UUID workspaceId, boolean includeArchived) {
        String sql = includeArchived
                ? "SELECT id, workspace_id, name, template_key, current_version, created_at, updated_at, archived_at FROM apps WHERE workspace_id = :wid ORDER BY created_at ASC"
                : "SELECT id, workspace_id, name, template_key, current_version, created_at, updated_at, archived_at FROM apps WHERE workspace_id = :wid AND archived_at IS NULL ORDER BY created_at ASC";

        return jdbcClient.sql(sql)
                .param("wid", workspaceId)
                .query(APP_ROW_MAPPER)
                .list();
    }

    public Optional<AppDefinitionVersionRecord> findVersion(UUID appId, int version) {
        return jdbcClient.sql("""
                SELECT id, app_id, version, definition::text AS definition, note, created_by, created_at
                FROM app_definition_versions
                WHERE app_id = :aid AND version = :version
                """)
                .param("aid", appId)
                .param("version", version)
                .query(VERSION_ROW_MAPPER)
                .optional();
    }

    public List<AppVersionSummaryRecord> findVersionSummaries(UUID appId) {
        return jdbcClient.sql("""
                SELECT id, app_id, version, note, created_by, created_at
                FROM app_definition_versions
                WHERE app_id = :aid
                ORDER BY version ASC
                """)
                .param("aid", appId)
                .query(VERSION_SUMMARY_ROW_MAPPER)
                .list();
    }

    public long countVersions(UUID appId) {
        return jdbcClient.sql("SELECT count(*) FROM app_definition_versions WHERE app_id = :aid")
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public int updateAppDefinitionAndVersion(UUID appId, UUID workspaceId, int expectedVersion, int newVersion, String newName, Instant now) {
        return jdbcClient.sql("""
                UPDATE apps
                SET current_version = :newVersion, name = :name, updated_at = :updatedAt
                WHERE id = :aid AND workspace_id = :wid AND current_version = :expectedVersion AND archived_at IS NULL
                """)
                .param("newVersion", newVersion)
                .param("name", newName)
                .param("updatedAt", Timestamp.from(now))
                .param("aid", appId)
                .param("wid", workspaceId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    public int archiveApp(UUID appId, UUID workspaceId, Instant now) {
        return jdbcClient.sql("""
                UPDATE apps
                SET archived_at = :archivedAt, updated_at = :updatedAt
                WHERE id = :aid AND workspace_id = :wid AND archived_at IS NULL
                """)
                .param("archivedAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .param("aid", appId)
                .param("wid", workspaceId)
                .update();
    }
}
