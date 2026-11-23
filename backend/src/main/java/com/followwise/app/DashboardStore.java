package com.followwise.app;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class DashboardStore {

    public record QuerySpec(String sql, Map<String, Object> params) {}

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

    QuerySpec countOverdueChargesQuery(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_totals AS (
                    SELECT charge_id,
                           SUM(amount_cents)::bigint AS outstanding,
                           COALESCE(BOOL_OR(reverses_entry_id = charge_id), false) AS reversed
                    FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                    GROUP BY charge_id
                )
                SELECT COUNT(c.id)::bigint
                FROM ledger_entries c
                JOIN charge_totals ct ON ct.charge_id = c.id
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.kind IN ('recurring', 'manual', 'late_fee')
                  AND c.due_date < :today
                  AND ct.outstanding > 0
                  AND NOT ct.reversed
                  AND p.archived_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("today", today);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        return new QuerySpec(sql.toString(), params);
    }

    public long countOverdueCharges(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        QuerySpec q = countOverdueChargesQuery(workspaceId, appId, partyType, today);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }

    QuerySpec sumOverdueChargesQuery(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_totals AS (
                    SELECT charge_id,
                           SUM(amount_cents)::bigint AS outstanding,
                           COALESCE(BOOL_OR(reverses_entry_id = charge_id), false) AS reversed
                    FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                    GROUP BY charge_id
                )
                SELECT COALESCE(SUM(ct.outstanding), 0)::bigint
                FROM ledger_entries c
                JOIN charge_totals ct ON ct.charge_id = c.id
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.kind IN ('recurring', 'manual', 'late_fee')
                  AND c.due_date < :today
                  AND ct.outstanding > 0
                  AND NOT ct.reversed
                  AND p.archived_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("today", today);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        return new QuerySpec(sql.toString(), params);
    }

    public long sumOverdueCharges(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        QuerySpec q = sumOverdueChargesQuery(workspaceId, appId, partyType, today);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }

    QuerySpec sumExpectedPeriodQuery(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_totals AS (
                    SELECT charge_id,
                           COALESCE(BOOL_OR(reverses_entry_id = charge_id), false) AS reversed
                    FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                    GROUP BY charge_id
                )
                SELECT COALESCE(SUM(c.amount_cents), 0)::bigint
                FROM ledger_entries c
                JOIN charge_totals ct ON ct.charge_id = c.id
                JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.due_date >= :monthStart AND c.due_date <= :monthEnd
                  AND NOT ct.reversed
                  AND p.archived_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("monthStart", monthStart);
        params.put("monthEnd", monthEnd);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        return new QuerySpec(sql.toString(), params);
    }

    public long sumExpectedPeriod(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        QuerySpec q = sumExpectedPeriodQuery(workspaceId, appId, partyType, monthStart, monthEnd);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }

    QuerySpec sumCollectedPeriodQuery(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        StringBuilder sql = new StringBuilder("""
                WITH payment_reversals AS (
                    SELECT reverses_entry_id
                    FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                      AND entry_type = 'REVERSAL'
                      AND reverses_entry_id IS NOT NULL
                )
                SELECT COALESCE(SUM(-py.amount_cents), 0)::bigint
                FROM ledger_entries py
                JOIN parties p ON p.id = py.party_id AND p.workspace_id = py.workspace_id AND p.app_id = py.app_id
                LEFT JOIN payment_reversals pr ON pr.reverses_entry_id = py.id
                WHERE py.workspace_id = :wid AND py.app_id = :aid
                  AND py.entry_type = 'PAYMENT'
                  AND py.paid_on >= :monthStart AND py.paid_on <= :monthEnd
                  AND p.archived_at IS NULL
                  AND pr.reverses_entry_id IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("monthStart", monthStart);
        params.put("monthEnd", monthEnd);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        return new QuerySpec(sql.toString(), params);
    }

    public long sumCollectedPeriod(UUID workspaceId, UUID appId, String partyType, LocalDate monthStart, LocalDate monthEnd) {
        QuerySpec q = sumCollectedPeriodQuery(workspaceId, appId, partyType, monthStart, monthEnd);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }

    QuerySpec listOverduePartiesQuery(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                WITH charge_totals AS (
                    SELECT charge_id,
                           SUM(amount_cents)::bigint AS outstanding,
                           COALESCE(BOOL_OR(reverses_entry_id = charge_id), false) AS reversed
                    FROM ledger_entries
                    WHERE workspace_id = :wid AND app_id = :aid
                    GROUP BY charge_id
                ),
                overdue_charges AS (
                    SELECT c.party_id,
                           c.id AS charge_id,
                           c.due_date,
                           ct.outstanding
                    FROM ledger_entries c
                    JOIN charge_totals ct ON ct.charge_id = c.id
                    JOIN parties p ON p.id = c.party_id AND p.workspace_id = c.workspace_id AND p.app_id = c.app_id
                    WHERE c.workspace_id = :wid AND c.app_id = :aid
                  AND c.entry_type = 'CHARGE'
                  AND c.kind IN ('recurring', 'manual', 'late_fee')
                  AND c.due_date < :today
                  AND ct.outstanding > 0
                  AND NOT ct.reversed
                  AND p.archived_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("today", today);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        sql.append("""
                ),
                party_overdue AS (
                    SELECT party_id,
                           SUM(outstanding)::bigint AS overdue_cents,
                           MIN(due_date) AS oldest_due_date,
                           COUNT(*)::bigint AS overdue_charge_count
                    FROM overdue_charges
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
                    JOIN parties p ON p.id = po.party_id AND p.workspace_id = :wid AND p.app_id = :aid
                )
                SELECT * FROM ranked
                ORDER BY overdue_cents DESC, party_id ASC
                LIMIT 20
                """);
        return new QuerySpec(sql.toString(), params);
    }

    public DashboardResponse.ListData<DashboardResponse.OverduePartyItem> listOverdueParties(
            UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        QuerySpec q = listOverduePartiesQuery(workspaceId, appId, partyType, today);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);

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

    QuerySpec listExpiringLeasesQuery(
            UUID workspaceId, UUID appId, String partyType, String dateField, LocalDate today, LocalDate todayPlus60) {
        StringBuilder sql = new StringBuilder("""
                WITH valid_dates AS (
                    SELECT p.id AS party_id,
                           p.display_name,
                           CASE WHEN jsonb_exists(p.data, :dateField)
                                     AND (p.data ->> :dateField) ~ '^\\d{4}-\\d{2}-\\d{2}$'
                                     AND pg_input_is_valid(p.data ->> :dateField, 'date')
                                THEN (p.data ->> :dateField)::date
                                ELSE NULL END AS date_val
                    FROM parties p
                    WHERE p.workspace_id = :wid AND p.app_id = :aid
                      AND p.archived_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("dateField", dateField);
        params.put("today", today);
        params.put("todayPlus60", todayPlus60);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        sql.append("""
                ),
                filtered AS (
                    SELECT party_id,
                           display_name,
                           date_val,
                           (date_val - :today)::int AS days_left,
                           COUNT(*) OVER() AS total_count
                    FROM valid_dates
                    WHERE date_val IS NOT NULL
                      AND date_val BETWEEN :today AND :todayPlus60
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
        return new QuerySpec(sql.toString(), params);
    }

    public DashboardResponse.ListData<DashboardResponse.ExpiringLeaseItem> listExpiringLeases(
            UUID workspaceId, UUID appId, String partyType, String dateField, LocalDate today, LocalDate todayPlus60) {
        QuerySpec q = listExpiringLeasesQuery(workspaceId, appId, partyType, dateField, today, todayPlus60);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);

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

    QuerySpec listMissingDocsQuery(UUID workspaceId, UUID appId, String partyType) {
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
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
                    JOIN parties p ON p.id = ms.party_id AND p.workspace_id = :wid AND p.app_id = :aid
                )
                SELECT * FROM ranked
                ORDER BY oldest_due_date ASC NULLS LAST, party_id ASC
                LIMIT 20
                """);
        return new QuerySpec(sql.toString(), params);
    }

    public DashboardResponse.ListData<DashboardResponse.MissingDocsPartyItem> listMissingDocs(
            UUID workspaceId, UUID appId, String partyType) {
        QuerySpec q = listMissingDocsQuery(workspaceId, appId, partyType);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);

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

    QuerySpec listExpiringDocsQuery(
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("today", today);
        params.put("todayPlus30", todayPlus30);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        sql.append("""
                )
                SELECT * FROM filtered
                ORDER BY expires_on ASC, request_id ASC
                LIMIT 20
                """);
        return new QuerySpec(sql.toString(), params);
    }

    public DashboardResponse.ListData<DashboardResponse.ExpiringDocItem> listExpiringDocs(
            UUID workspaceId, UUID appId, String partyType, LocalDate today, LocalDate todayPlus30) {
        QuerySpec q = listExpiringDocsQuery(workspaceId, appId, partyType, today, todayPlus30);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);

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

    QuerySpec completionPctByPartyQuery(UUID workspaceId, UUID appId, String partyType) {
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
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
        return new QuerySpec(sql.toString(), params);
    }

    public DashboardResponse.CompletionPctData completionPctByParty(
            UUID workspaceId, UUID appId, String partyType) {
        QuerySpec q = completionPctByPartyQuery(workspaceId, appId, partyType);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);

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

    QuerySpec countOverdueDocsQuery(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("wid", workspaceId);
        params.put("aid", appId);
        params.put("today", today);
        if (partyType != null) {
            sql.append(" AND p.party_type = :partyType");
            params.put("partyType", partyType);
        }
        return new QuerySpec(sql.toString(), params);
    }

    public long countOverdueDocs(UUID workspaceId, UUID appId, String partyType, LocalDate today) {
        QuerySpec q = countOverdueDocsQuery(workspaceId, appId, partyType, today);
        var spec = jdbcClient.sql(q.sql());
        q.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }
}
