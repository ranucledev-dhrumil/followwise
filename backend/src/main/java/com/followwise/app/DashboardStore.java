package com.followwise.app;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class DashboardStore {

    protected final JdbcClient jdbcClient;

    public DashboardStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void applyStatementTimeout(int timeoutMs) {
        jdbcClient.sql("SELECT set_config('statement_timeout', CAST(:ms AS text), true)")
                .param("ms", timeoutMs)
                .query(String.class)
                .single();
    }

    public void beforeFirstWidgetQuery() {
        // hook for test subclasses
    }

    public long countOverdueCharges(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(c.id)
                FROM ledger_entries c
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.kind IN ('recurring', 'manual', 'late_fee')
                  AND c.due_date < :today
                  AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                  AND NOT EXISTS (
                      SELECT 1 FROM ledger_entries r
                      WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                  )
                  AND (
                      SELECT COALESCE(SUM(e.amount_cents), 0)::bigint
                      FROM ledger_entries e
                      WHERE e.workspace_id = c.workspace_id AND e.app_id = c.app_id AND e.charge_id = c.id
                  ) > 0
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }
        return spec.query(Long.class).single();
    }

    public long sumOverdueCharges(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH overdue_charges AS (
                    SELECT c.id,
                           (SELECT COALESCE(SUM(e.amount_cents), 0)::bigint
                            FROM ledger_entries e
                            WHERE e.workspace_id = c.workspace_id AND e.app_id = c.app_id AND e.charge_id = c.id) AS outstanding
                    FROM ledger_entries c
                    JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid
                      AND c.entry_type = 'CHARGE'
                      AND c.kind IN ('recurring', 'manual', 'late_fee')
                      AND c.due_date < :today
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries r
                          WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                      )
                )
                SELECT COALESCE(SUM(outstanding), 0)::bigint
                FROM overdue_charges
                WHERE outstanding > 0
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }
        return spec.query(Long.class).single();
    }

    public long sumExpectedPeriod(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(c.amount_cents), 0)::bigint
                FROM ledger_entries c
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.due_date >= :monthStart AND c.due_date <= :monthEnd
                  AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                  AND NOT EXISTS (
                      SELECT 1 FROM ledger_entries r
                      WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                  )
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("monthStart", monthStart)
                .param("monthEnd", monthEnd);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }
        return spec.query(Long.class).single();
    }

    public long sumCollectedPeriod(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(-py.amount_cents), 0)::bigint
                FROM ledger_entries py
                JOIN parties p ON p.id = py.party_id AND p.workspace_id = py.workspace_id AND p.app_id = py.app_id
                WHERE py.workspace_id = :wid AND py.app_id = :aid
                  AND py.entry_type = 'PAYMENT'
                  AND py.paid_on >= :monthStart AND py.paid_on <= :monthEnd
                  AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                  AND NOT EXISTS (
                      SELECT 1 FROM ledger_entries r
                      WHERE r.workspace_id = py.workspace_id AND r.app_id = py.app_id AND r.charge_id = py.charge_id AND r.reverses_entry_id = py.id
                  )
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("monthStart", monthStart)
                .param("monthEnd", monthEnd);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }
        return spec.query(Long.class).single();
    }

    public DashboardResponse.ListData<DashboardResponse.OverduePartyItem> listOverdueParties(
            UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH overdue_charges AS (
                    SELECT c.party_id,
                           c.id AS charge_id,
                           c.due_date,
                           (SELECT COALESCE(SUM(e.amount_cents), 0)::bigint
                            FROM ledger_entries e
                            WHERE e.workspace_id = c.workspace_id AND e.app_id = c.app_id AND e.charge_id = c.id) AS outstanding
                    FROM ledger_entries c
                    JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid
                      AND c.entry_type = 'CHARGE'
                      AND c.kind IN ('recurring', 'manual', 'late_fee')
                      AND c.due_date < :today
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries r
                          WHERE r.workspace_id = c.workspace_id AND r.app_id = c.app_id AND r.charge_id = c.id AND r.reverses_entry_id = c.id
                      )
                ),
                party_overdue AS (
                    SELECT party_id,
                           SUM(outstanding)::bigint AS overdue_cents,
                           MIN(due_date) AS oldest_due_date,
                           COUNT(*)::bigint AS overdue_charge_count
                    FROM overdue_charges
                    WHERE outstanding > 0
                    GROUP BY party_id
                ),
                ranked AS (
                    SELECT p.id AS party_id,
                           p.display_name,
                           po.overdue_cents,
                           po.oldest_due_date,
                           po.overdue_charge_count,
                           COUNT(*) OVER() AS total_count
                    FROM party_overdue po
                    JOIN parties p ON p.id = po.party_id
                )
                SELECT * FROM ranked
                ORDER BY overdue_cents DESC, party_id ASC
                LIMIT 20
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }

        List<DashboardResponse.OverduePartyItem> items = new ArrayList<>();
        long[] totalCount = new long[]{0};
        spec.query((rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            items.add(new DashboardResponse.OverduePartyItem(
                    rs.getObject("party_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getLong("overdue_cents"),
                    rs.getObject("oldest_due_date", LocalDate.class),
                    rs.getLong("overdue_charge_count")
            ));
            return null;
        }).list();

        return new DashboardResponse.ListData<>(items, totalCount[0]);
    }

    public DashboardResponse.ListData<DashboardResponse.ExpiringLeaseItem> listExpiringLeases(
            UUID workspaceId, UUID appId, String partyType, String dateField, LocalDate today, LocalDate todayPlus60) {
        StringBuilder sql = new StringBuilder("""
                WITH filtered AS (
                    SELECT p.id AS party_id,
                           p.display_name,
                           (p.data ->> :dateField)::date AS date_val,
                           ((p.data ->> :dateField)::date - :today)::int AS days_left,
                           COUNT(*) OVER() AS total_count
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                      AND jsonb_exists(p.data, :dateField)
                      AND (p.data ->> :dateField) ~ '^\\d{4}-\\d{2}-\\d{2}$'
                      AND (p.data ->> :dateField)::date BETWEEN :today AND :todayPlus60
                )
                SELECT party_id,
                       display_name,
                       date_val,
                       days_left,
                       total_count
                FROM filtered
                ORDER BY date_val ASC, party_id ASC
                LIMIT 20
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("dateField", dateField)
                .param("today", today)
                .param("todayPlus60", todayPlus60);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }

        List<DashboardResponse.ExpiringLeaseItem> items = new ArrayList<>();
        long[] totalCount = new long[]{0};
        spec.query((rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            items.add(new DashboardResponse.ExpiringLeaseItem(
                    rs.getObject("party_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getObject("date_val", LocalDate.class),
                    rs.getInt("days_left")
            ));
            return null;
        }).list();

        return new DashboardResponse.ListData<>(items, totalCount[0]);
    }

    public DashboardResponse.ListData<DashboardResponse.MissingDocsPartyItem> listMissingDocs(
            UUID workspaceId, UUID appId, String partyType) {
        StringBuilder sql = new StringBuilder("""
                WITH missing_summary AS (
                    SELECT r.party_id,
                           COUNT(*)::bigint AS missing_count,
                           MIN(r.due_date) AS oldest_due_date
                    FROM document_requests r
                    JOIN parties p ON p.id = r.party_id AND p.workspace_id = r.workspace_id AND p.app_id = r.app_id
                    WHERE r.workspace_id = :wid AND r.app_id = :aid
                      AND r.required = true
                      AND r.status IN ('PENDING', 'REJECTED')
                      AND r.cancelled_at IS NULL
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                    GROUP BY r.party_id
                ),
                ranked AS (
                    SELECT ms.party_id,
                           p.display_name,
                           ms.missing_count,
                           ms.oldest_due_date,
                           COUNT(*) OVER() AS total_count
                    FROM missing_summary ms
                    JOIN parties p ON p.id = ms.party_id
                )
                SELECT * FROM ranked
                ORDER BY oldest_due_date ASC NULLS LAST, party_id ASC
                LIMIT 20
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }

        List<DashboardResponse.MissingDocsPartyItem> items = new ArrayList<>();
        long[] totalCount = new long[]{0};
        spec.query((rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            items.add(new DashboardResponse.MissingDocsPartyItem(
                    rs.getObject("party_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getLong("missing_count"),
                    rs.getObject("oldest_due_date", LocalDate.class)
            ));
            return null;
        }).list();

        return new DashboardResponse.ListData<>(items, totalCount[0]);
    }

    public DashboardResponse.ListData<DashboardResponse.ExpiringDocItem> listExpiringDocs(
            UUID workspaceId, UUID appId, String partyType, LocalDate today, LocalDate todayPlus30) {
        StringBuilder sql = new StringBuilder("""
                WITH filtered AS (
                    SELECT r.id AS request_id,
                           r.party_id,
                           p.display_name,
                           r.label,
                           r.expires_on,
                           (r.expires_on - :today)::int AS days_left,
                           COUNT(*) OVER() AS total_count
                    FROM document_requests r
                    JOIN parties p ON p.id = r.party_id AND p.workspace_id = r.workspace_id AND p.app_id = r.app_id
                    WHERE r.workspace_id = :wid AND r.app_id = :aid
                      AND r.status = 'APPROVED'
                      AND r.cancelled_at IS NULL
                      AND r.expires_on BETWEEN :today AND :todayPlus30
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                )
                SELECT * FROM filtered
                ORDER BY expires_on ASC, request_id ASC
                LIMIT 20
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today)
                .param("todayPlus30", todayPlus30);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }

        List<DashboardResponse.ExpiringDocItem> items = new ArrayList<>();
        long[] totalCount = new long[]{0};
        spec.query((rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            items.add(new DashboardResponse.ExpiringDocItem(
                    rs.getObject("request_id", UUID.class),
                    rs.getObject("party_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getString("label"),
                    rs.getObject("expires_on", LocalDate.class),
                    rs.getInt("days_left")
            ));
            return null;
        }).list();

        return new DashboardResponse.ListData<>(items, totalCount[0]);
    }

    public DashboardResponse.CompletionPctData completionPctByParty(
            UUID workspaceId, UUID appId, String partyType) {
        StringBuilder sql = new StringBuilder("""
                WITH party_reqs AS (
                    SELECT r.party_id,
                           p.display_name,
                           COUNT(*)::bigint AS required_count,
                           COUNT(*) FILTER (WHERE r.status = 'APPROVED')::bigint AS approved_count
                    FROM document_requests r
                    JOIN parties p ON p.id = r.party_id AND p.workspace_id = r.workspace_id AND p.app_id = r.app_id
                    WHERE r.workspace_id = :wid AND r.app_id = :aid
                      AND r.required = true
                      AND r.cancelled_at IS NULL
                      AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }
        sql.append("""
                    GROUP BY r.party_id, p.display_name
                ),
                totals AS (
                    SELECT COALESCE(SUM(required_count), 0)::bigint AS all_req,
                           COALESCE(SUM(approved_count), 0)::bigint AS all_app,
                           COUNT(*)::bigint AS all_parties
                    FROM party_reqs
                ),
                ranked AS (
                    SELECT pr.party_id,
                           pr.display_name,
                           FLOOR((100.0 * pr.approved_count) / pr.required_count)::int AS pct,
                           pr.required_count,
                           pr.approved_count
                    FROM party_reqs pr
                    ORDER BY pct ASC, pr.party_id ASC
                    LIMIT 20
                )
                SELECT r.party_id,
                       r.display_name,
                       r.pct,
                       r.required_count,
                       r.approved_count,
                       t.all_parties AS total_count,
                       t.all_req,
                       t.all_app
                FROM totals t
                LEFT JOIN ranked r ON true
                """);

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }

        List<DashboardResponse.CompletionPctPartyItem> items = new ArrayList<>();
        long[] totalCount = new long[]{0};
        long[] allReq = new long[]{0};
        long[] allApp = new long[]{0};

        spec.query((rs, rowNum) -> {
            totalCount[0] = rs.getLong("total_count");
            allReq[0] = rs.getLong("all_req");
            allApp[0] = rs.getLong("all_app");
            UUID pid = rs.getObject("party_id", UUID.class);
            if (pid != null) {
                items.add(new DashboardResponse.CompletionPctPartyItem(
                        pid,
                        rs.getString("display_name"),
                        rs.getInt("pct"),
                        rs.getLong("required_count"),
                        rs.getLong("approved_count")
                ));
            }
            return null;
        }).list();

        int overallPct = allReq[0] > 0 ? (int) Math.floor((100.0 * allApp[0]) / allReq[0]) : 0;
        return new DashboardResponse.CompletionPctData(items, totalCount[0], overallPct);
    }

    public long countOverdueDocs(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)::bigint
                FROM document_requests r
                JOIN parties p ON p.id = r.party_id AND p.workspace_id = r.workspace_id AND p.app_id = r.app_id
                WHERE r.workspace_id = :wid AND r.app_id = :aid
                  AND r.required = true
                  AND r.status IN ('PENDING', 'REJECTED')
                  AND r.cancelled_at IS NULL
                  AND r.due_date < :today
                  AND p.archived_at IS NULL
                """);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
        }

        var spec = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("today", today);
        if (partyType != null) {
            spec.param("partyType", partyType);
        }
        return spec.query(Long.class).single();
    }
}
