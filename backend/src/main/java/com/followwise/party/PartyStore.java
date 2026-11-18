package com.followwise.party;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PartyStore {

    private final JdbcClient jdbcClient;

    private static final RowMapper<PartyRecord> PARTY_ROW_MAPPER = (rs, rowNum) -> new PartyRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getString("party_type"),
            rs.getString("display_name"),
            rs.getString("data"),
            rs.getInt("definition_version"),
            rs.getInt("version"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("updated_by", UUID.class),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("archived_at")),
            toInstant(rs.getTimestamp("reminders_paused_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public PartyStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> lockApp(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT id FROM apps WHERE id = :aid AND workspace_id = :wid AND archived_at IS NULL FOR UPDATE")
                .param("aid", appId)
                .param("wid", workspaceId)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> lockPartyRow(UUID workspaceId, UUID appId, UUID partyId) {
        return jdbcClient.sql("SELECT id FROM parties WHERE id = :pid AND workspace_id = :wid AND app_id = :aid FOR UPDATE")
                .param("pid", partyId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(UUID.class)
                .optional();
    }

    public long countActive(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT count(*) FROM parties WHERE workspace_id = :wid AND app_id = :aid AND archived_at IS NULL")
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public long countTotal(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("SELECT count(*) FROM parties WHERE workspace_id = :wid AND app_id = :aid")
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public void insert(UUID id, UUID workspaceId, UUID appId, String partyType, String displayName,
                       String dataJson, int defVersion, UUID createdBy, Instant now) {
        jdbcClient.sql("""
                INSERT INTO parties (
                    id, workspace_id, app_id, party_type, display_name, data,
                    definition_version, version, created_by, updated_by, created_at, updated_at, archived_at, reminders_paused_at
                ) VALUES (
                    :id, :wid, :aid, :partyType, :displayName, CAST(:data AS jsonb),
                    :defVersion, 1, :createdBy, :createdBy, :createdAt, :updatedAt, NULL, NULL
                )
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("partyType", partyType)
                .param("displayName", displayName)
                .param("data", dataJson)
                .param("defVersion", defVersion)
                .param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    public Optional<PartyRecord> findById(UUID workspaceId, UUID appId, UUID id) {
        return jdbcClient.sql("""
                SELECT id, workspace_id, app_id, party_type, display_name, data::text AS data,
                       definition_version, version, created_by, updated_by, created_at, updated_at, archived_at, reminders_paused_at
                FROM parties
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(PARTY_ROW_MAPPER)
                .optional();
    }

    public List<PartyRecord> findPage(UUID workspaceId, UUID appId, String partyType, String q,
                                      boolean includeArchived, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, workspace_id, app_id, party_type, display_name, data::text AS data,
                       definition_version, version, created_by, updated_by, created_at, updated_at, archived_at, reminders_paused_at
                FROM parties
                WHERE workspace_id = :wid AND app_id = :aid AND party_type = :partyType
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
                .param("partyType", partyType)
                .param("size", size)
                .param("offset", (long) page * size);

        if (hasQ) {
            String escaped = escapeLike(q.trim());
            spec.param("pattern", escaped.toLowerCase(Locale.ROOT) + "%");
        }

        return spec.query(PARTY_ROW_MAPPER).list();
    }

    public long countFiltered(UUID workspaceId, UUID appId, String partyType, String q, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*)
                FROM parties
                WHERE workspace_id = :wid AND app_id = :aid AND party_type = :partyType
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
                .param("partyType", partyType);

        if (hasQ) {
            String escaped = escapeLike(q.trim());
            spec.param("pattern", escaped.toLowerCase(Locale.ROOT) + "%");
        }

        return spec.query(Long.class).single();
    }

    public int update(UUID workspaceId, UUID appId, UUID id, int expectedVersion,
                      String displayName, String dataJson, int defVersion, UUID updatedBy, Instant now) {
        return jdbcClient.sql("""
                UPDATE parties
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
                UPDATE parties
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
                UPDATE parties
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
                SELECT party_type, archived_at
                FROM parties
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", targetId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query((rs, rowNum) -> {
                    String actualType = rs.getString("party_type");
                    boolean archived = rs.getTimestamp("archived_at") != null;
                    if (!actualType.equals(expectedType)) {
                        return RefChecker.TargetStatus.NOT_FOUND;
                    }
                    return archived ? RefChecker.TargetStatus.ARCHIVED : RefChecker.TargetStatus.VALID;
                })
                .optional()
                .orElse(RefChecker.TargetStatus.NOT_FOUND);
    }

    public List<PartyRecord> findActiveByPartyType(UUID workspaceId, UUID appId, String partyType) {
        return jdbcClient.sql("""
                SELECT id, workspace_id, app_id, party_type, display_name, data::text AS data,
                       definition_version, version, created_by, updated_by, created_at, updated_at, archived_at, reminders_paused_at
                FROM parties
                WHERE workspace_id = :wid AND app_id = :aid AND party_type = :partyType AND archived_at IS NULL
                ORDER BY id ASC
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("partyType", partyType)
                .query(PARTY_ROW_MAPPER)
                .list();
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public int pauseReminders(UUID workspaceId, UUID appId, UUID partyId, int expectedVersion, Instant now) {
        return jdbcClient.sql("""
                UPDATE parties
                SET reminders_paused_at = :pausedAt, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND version = :expectedVersion AND archived_at IS NULL
                """)
                .param("pausedAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .param("id", partyId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    public int resumeReminders(UUID workspaceId, UUID appId, UUID partyId, int expectedVersion, Instant now) {
        return jdbcClient.sql("""
                UPDATE parties
                SET reminders_paused_at = NULL, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND version = :expectedVersion AND archived_at IS NULL
                """)
                .param("updatedAt", Timestamp.from(now))
                .param("id", partyId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    public record OwnerEmail(UUID userId, String email) {}

    public List<OwnerEmail> findOwnersWithVerifiedEmail(UUID workspaceId) {
        return jdbcClient.sql("""
                SELECT u.id AS user_id, u.email
                FROM memberships m
                JOIN users u ON m.user_id = u.id
                WHERE m.workspace_id = :wid
                  AND m.role = 'OWNER'
                  AND u.email_verified_at IS NOT NULL
                ORDER BY u.created_at ASC
                LIMIT 5
                """)
                .param("wid", workspaceId)
                .query((rs, rowNum) -> new OwnerEmail(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email")
                ))
                .list();
    }

    public record ReminderDatePartyCandidate(
            UUID partyId,
            String displayName,
            String partyDataJson,
            LocalDate targetDate,
            boolean remindersPaused
    ) {}

    public List<ReminderDatePartyCandidate> findPartiesForDateReminder(
            UUID workspaceId, UUID appId, String partyType,
            String dateField, LocalDate minDate, LocalDate maxDate, int limit
    ) {
        return findPartiesForDateReminder(workspaceId, appId, partyType, dateField, minDate, maxDate, null, null, limit);
    }

    public List<ReminderDatePartyCandidate> findPartiesForDateReminder(
            UUID workspaceId, UUID appId, String partyType,
            String dateField, LocalDate minDate, LocalDate maxDate,
            LocalDate cursorTargetDate, UUID cursorPartyId,
            int limit
    ) {
        return findPartiesForDateReminder(workspaceId, appId, partyType, dateField, minDate, maxDate, cursorTargetDate, cursorPartyId, cursorPartyId, limit);
    }

    public List<ReminderDatePartyCandidate> findPartiesForDateReminder(
            UUID workspaceId, UUID appId, String partyType,
            String dateField, LocalDate minDate, LocalDate maxDate,
            LocalDate cursorTargetDate, UUID cursorPartyId, UUID cursorSubjectId,
            int limit
    ) {
        String sql = """
                SELECT p.id, p.display_name, p.data::text AS data,
                       (p.data ->> :dateField)::date AS target_date,
                       (p.reminders_paused_at IS NOT NULL) AS reminders_paused
                FROM parties p
                WHERE p.workspace_id = :wid
                  AND p.app_id = :aid
                  AND p.party_type = :partyType
                  AND p.archived_at IS NULL
                  AND p.data ->> :dateField ~ '^\\d{4}-\\d{2}-\\d{2}$'
                  AND (p.data ->> :dateField)::date BETWEEN :minDate AND :maxDate
                  AND (
                      :cursorTargetDate::date IS NULL
                      OR ((p.data ->> :dateField)::date, p.id, p.id) > (:cursorTargetDate::date, :cursorPartyId::uuid, :cursorSubjectId::uuid)
                  )
                ORDER BY (p.data ->> :dateField)::date ASC, p.id ASC, p.id ASC
                LIMIT :limit
                """;

        return jdbcClient.sql(sql)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("partyType", partyType)
                .param("dateField", dateField)
                .param("minDate", minDate)
                .param("maxDate", maxDate)
                .param("cursorTargetDate", cursorTargetDate)
                .param("cursorPartyId", cursorPartyId)
                .param("cursorSubjectId", cursorSubjectId)
                .param("limit", limit)
                .query((rs, rowNum) -> new ReminderDatePartyCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("data"),
                        rs.getObject("target_date", LocalDate.class),
                        rs.getBoolean("reminders_paused")
                ))
                .list();
    }
}
