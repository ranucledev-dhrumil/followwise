package com.followwise.ledger;

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
public class LedgerStore {

    private final JdbcClient jdbcClient;

    public record ChargeSummaryRecord(
            LedgerEntry chargeEntry,
            long outstandingCents,
            String status
    ) {}

    private static final RowMapper<LedgerEntry> ENTRY_ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getObject("charge_id", UUID.class),
            rs.getString("entry_type"),
            rs.getLong("amount_cents"),
            rs.getString("currency"),
            rs.getString("kind"),
            rs.getString("schedule_id"),
            rs.getString("period_key"),
            rs.getString("label"),
            rs.getObject("due_date", LocalDate.class),
            rs.getObject("paid_on", LocalDate.class),
            rs.getString("note"),
            rs.getObject("reverses_entry_id", UUID.class),
            rs.getObject("idempotency_key", UUID.class),
            rs.getString("request_hash"),
            rs.getObject("created_by", UUID.class),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<ChargeSummaryRecord> CHARGE_SUMMARY_ROW_MAPPER = (rs, rowNum) -> {
        LedgerEntry entry = ENTRY_ROW_MAPPER.mapRow(rs, rowNum);
        long outstanding = rs.getLong("outstanding_cents");
        String status = rs.getString("derived_status");
        return new ChargeSummaryRecord(entry, outstanding, status);
    };

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public LedgerStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Instant insertEntry(UUID id, UUID workspaceId, UUID appId, UUID partyId, UUID chargeId,
                               String entryType, long amountCents, String currency, String kind,
                               String scheduleId, String periodKey, String label, LocalDate dueDate,
                               LocalDate paidOn, String note, UUID reversesEntryId,
                               UUID idempotencyKey, String requestHash, UUID createdBy) {
        return jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, kind, schedule_id, period_key,
                    label, due_date, paid_on, note, reverses_entry_id,
                    idempotency_key, request_hash, created_by
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, :entryType,
                    :amountCents, :currency, :kind, :scheduleId, :periodKey,
                    :label, :dueDate, :paidOn, :note, :reversesEntryId,
                    :idempotencyKey, :requestHash, :createdBy
                ) RETURNING created_at
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("cid", chargeId)
                .param("entryType", entryType)
                .param("amountCents", amountCents)
                .param("currency", currency)
                .param("kind", kind)
                .param("scheduleId", scheduleId)
                .param("periodKey", periodKey)
                .param("label", label)
                .param("dueDate", dueDate)
                .param("paidOn", paidOn)
                .param("note", note)
                .param("reversesEntryId", reversesEntryId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .param("createdBy", createdBy)
                .query((rs, rowNum) -> rs.getTimestamp("created_at").toInstant())
                .single();
    }

    public Optional<LedgerEntry> findEntryById(UUID workspaceId, UUID appId, UUID entryId) {
        return jdbcClient.sql("""
                SELECT * FROM ledger_entries
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid
                """)
                .param("id", entryId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(ENTRY_ROW_MAPPER)
                .optional();
    }

    public Optional<LedgerEntry> findChargeById(UUID workspaceId, UUID appId, UUID chargeId) {
        return jdbcClient.sql("""
                SELECT * FROM ledger_entries
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND entry_type = 'CHARGE'
                """)
                .param("id", chargeId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(ENTRY_ROW_MAPPER)
                .optional();
    }

    public Optional<UUID> lockChargeRow(UUID workspaceId, UUID appId, UUID chargeId) {
        return jdbcClient.sql("""
                SELECT id FROM ledger_entries
                WHERE id = :id AND workspace_id = :wid AND app_id = :aid AND entry_type = 'CHARGE'
                FOR UPDATE
                """)
                .param("id", chargeId)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(UUID.class)
                .optional();
    }

    public long computeOutstanding(UUID workspaceId, UUID appId, UUID chargeId) {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(amount_cents), 0)::bigint
                FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND charge_id = :cid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("cid", chargeId)
                .query(Long.class)
                .single();
    }

    public boolean isChargeReversed(UUID workspaceId, UUID appId, UUID chargeId) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                      AND charge_id = :cid AND reverses_entry_id = :cid
                )
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("cid", chargeId)
                .query(Boolean.class)
                .single();
    }

    public long countTotal(UUID workspaceId, UUID appId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .query(Long.class)
                .single();
    }

    public Optional<LedgerEntry> findByIdempotencyKey(UUID appId, UUID idempotencyKey) {
        return jdbcClient.sql("""
                SELECT * FROM ledger_entries
                WHERE app_id = :aid AND idempotency_key = :key
                """)
                .param("aid", appId)
                .param("key", idempotencyKey)
                .query(ENTRY_ROW_MAPPER)
                .optional();
    }

    public List<LedgerEntry> findByParty(UUID workspaceId, UUID appId, UUID partyId, int page, int size) {
        return jdbcClient.sql("""
                SELECT * FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                ORDER BY created_at ASC, id ASC
                LIMIT :size OFFSET :offset
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(ENTRY_ROW_MAPPER)
                .list();
    }

    public long countByParty(UUID workspaceId, UUID appId, UUID partyId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .query(Long.class)
                .single();
    }

    public List<LedgerEntry> findEntriesByChargeId(UUID workspaceId, UUID appId, UUID chargeId) {
        return jdbcClient.sql("""
                SELECT * FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND charge_id = :cid
                ORDER BY created_at ASC, id ASC
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("cid", chargeId)
                .query(ENTRY_ROW_MAPPER)
                .list();
    }

    public LedgerSummaryResponse computePartySummary(UUID workspaceId, UUID appId, UUID partyId) {
        return jdbcClient.sql("""
                SELECT
                    COALESCE(SUM(CASE WHEN entry_type = 'CHARGE' OR (entry_type = 'REVERSAL' AND amount_cents < 0) THEN amount_cents ELSE 0 END), 0)::bigint AS total_charged,
                    COALESCE(SUM(CASE WHEN entry_type = 'PAYMENT' OR (entry_type = 'REVERSAL' AND amount_cents > 0) THEN -amount_cents ELSE 0 END), 0)::bigint AS total_paid,
                    COALESCE(SUM(amount_cents), 0)::bigint AS balance
                FROM ledger_entries
                WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .query((rs, rowNum) -> new LedgerSummaryResponse(
                        rs.getLong("total_charged"),
                        rs.getLong("total_paid"),
                        rs.getLong("balance")
                ))
                .single();
    }

    public List<ChargeSummaryRecord> findChargesPage(UUID workspaceId, UUID appId, UUID partyId,
                                                    String status, LocalDate today, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_summaries AS (
                    SELECT c.id, c.workspace_id, c.app_id, c.party_id, c.charge_id, c.entry_type,
                           c.amount_cents, c.currency, c.kind, c.schedule_id, c.period_key,
                           c.label, c.due_date, c.paid_on, c.note, c.reverses_entry_id,
                           c.idempotency_key, c.request_hash, c.created_by, c.created_at,
                           COALESCE(SUM(e.amount_cents), 0)::bigint AS outstanding_cents,
                           CASE
                               WHEN COALESCE(BOOL_OR(e.reverses_entry_id = c.id), false) THEN 'reversed'
                               WHEN COALESCE(SUM(e.amount_cents), 0) = 0 THEN 'paid'
                               WHEN c.due_date < :today THEN 'overdue'
                               ELSE 'open'
                           END AS derived_status
                    FROM ledger_entries c
                    LEFT JOIN ledger_entries e ON e.charge_id = c.id AND e.workspace_id = c.workspace_id AND e.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid AND c.entry_type = 'CHARGE'
                """);

        if (partyId != null) {
            sql.append(" AND c.party_id = :partyId");
        }

        sql.append("""
                    GROUP BY c.id
                )
                SELECT * FROM charge_summaries
                WHERE 1=1
                """);

        if (status != null && !status.equalsIgnoreCase("all")) {
            sql.append(" AND derived_status = :status");
        }

        sql.append(" ORDER BY due_date ASC, id ASC LIMIT :size OFFSET :offset");

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today)
                .param("size", size)
                .param("offset", (long) page * size);

        if (partyId != null) {
            spec.param("partyId", partyId);
        }
        if (status != null && !status.equalsIgnoreCase("all")) {
            spec.param("status", status.toLowerCase(Locale.ROOT));
        }

        return spec.query(CHARGE_SUMMARY_ROW_MAPPER).list();
    }

    public long countChargesFiltered(UUID workspaceId, UUID appId, UUID partyId, String status, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_summaries AS (
                    SELECT c.id, c.workspace_id, c.app_id, c.party_id, c.charge_id, c.entry_type,
                           c.amount_cents, c.currency, c.kind, c.schedule_id, c.period_key,
                           c.label, c.due_date, c.paid_on, c.note, c.reverses_entry_id,
                           c.idempotency_key, c.request_hash, c.created_by, c.created_at,
                           COALESCE(SUM(e.amount_cents), 0)::bigint AS outstanding_cents,
                           CASE
                               WHEN COALESCE(BOOL_OR(e.reverses_entry_id = c.id), false) THEN 'reversed'
                               WHEN COALESCE(SUM(e.amount_cents), 0) = 0 THEN 'paid'
                               WHEN c.due_date < :today THEN 'overdue'
                               ELSE 'open'
                           END AS derived_status
                    FROM ledger_entries c
                    LEFT JOIN ledger_entries e ON e.charge_id = c.id AND e.workspace_id = c.workspace_id AND e.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid AND c.entry_type = 'CHARGE'
                """);

        if (partyId != null) {
            sql.append(" AND c.party_id = :partyId");
        }

        sql.append("""
                    GROUP BY c.id
                )
                SELECT count(*) FROM charge_summaries
                WHERE 1=1
                """);

        if (status != null && !status.equalsIgnoreCase("all")) {
            sql.append(" AND derived_status = :status");
        }

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today);

        if (partyId != null) {
            spec.param("partyId", partyId);
        }
        if (status != null && !status.equalsIgnoreCase("all")) {
            spec.param("status", status.toLowerCase(Locale.ROOT));
        }

        return spec.query(Long.class).single();
    }

    public int insertEntryOnConflictDoNothing(UUID id, UUID workspaceId, UUID appId, UUID partyId, UUID chargeId,
                                             String entryType, long amountCents, String currency, String kind,
                                             String scheduleId, String periodKey, String label, LocalDate dueDate,
                                             LocalDate paidOn, String note, UUID reversesEntryId,
                                             UUID idempotencyKey, String requestHash, UUID createdBy) {
        return jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, kind, schedule_id, period_key,
                    label, due_date, paid_on, note, reverses_entry_id,
                    idempotency_key, request_hash, created_by
                ) VALUES (
                    :id, :wid, :aid, :pid, :cid, :entryType,
                    :amountCents, :currency, :kind, :scheduleId, :periodKey,
                    :label, :dueDate, :paidOn, :note, :reversesEntryId,
                    :idempotencyKey, :requestHash, :createdBy
                )
                ON CONFLICT (app_id, party_id, schedule_id, period_key)
                WHERE entry_type = 'CHARGE' AND period_key IS NOT NULL
                DO NOTHING
                """)
                .param("id", id)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("cid", chargeId)
                .param("entryType", entryType)
                .param("amountCents", amountCents)
                .param("currency", currency)
                .param("kind", kind)
                .param("scheduleId", scheduleId)
                .param("periodKey", periodKey)
                .param("label", label)
                .param("dueDate", dueDate)
                .param("paidOn", paidOn)
                .param("note", note)
                .param("reversesEntryId", reversesEntryId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .param("createdBy", createdBy)
                .update();
    }

    public boolean existsSchedulePeriodCharge(UUID workspaceId, UUID appId, UUID partyId, String scheduleId, String periodKey) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid AND party_id = :pid
                      AND schedule_id = :sid AND period_key = :pkey
                      AND entry_type = 'CHARGE'
                )
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("pid", partyId)
                .param("sid", scheduleId)
                .param("pkey", periodKey)
                .query(Boolean.class)
                .single();
    }

    public record OverdueChargeCandidate(
            UUID chargeId,
            UUID partyId,
            String label,
            LocalDate dueDate,
            String partyDataJson
    ) {}

    public List<OverdueChargeCandidate> findOverdueChargesForLateFee(
            UUID workspaceId, UUID appId, String partyType, int graceDays, LocalDate today) {
        return jdbcClient.sql("""
                SELECT c.id AS charge_id, c.party_id, c.label, c.due_date, p.data::text AS party_data
                FROM ledger_entries c
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid
                  AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.kind IN ('recurring', 'manual')
                  AND p.party_type = :partyType
                  AND p.archived_at IS NULL
                  AND (c.due_date + CAST(:graceDays AS integer)) < :today
                  AND NOT EXISTS (
                      SELECT 1 FROM ledger_entries r
                      WHERE r.workspace_id = c.workspace_id
                        AND r.app_id = c.app_id
                        AND r.charge_id = c.id
                        AND r.reverses_entry_id = c.id
                  )
                  AND (
                      SELECT COALESCE(SUM(amount_cents), 0)::bigint
                      FROM ledger_entries e
                      WHERE e.workspace_id = c.workspace_id
                        AND e.app_id = c.app_id
                        AND e.charge_id = c.id
                  ) > 0
                ORDER BY c.due_date ASC, c.id ASC
                """)
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("partyType", partyType)
                .param("graceDays", graceDays)
                .param("today", today)
                .query((rs, rowNum) -> new OverdueChargeCandidate(
                        rs.getObject("charge_id", UUID.class),
                        rs.getObject("party_id", UUID.class),
                        rs.getString("label"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getString("party_data")
                ))
                .list();
    }
}
