package com.followwise.app;

import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class DashboardService {

    static final Map<String, String> WIDGET_LABELS = Map.of(
            "count_overdue_charges", "Overdue charges",
            "sum_overdue_charges", "Overdue amount",
            "sum_expected_period", "Expected (selected month)",
            "sum_collected_period", "Collected (selected month)",
            "list_overdue_parties", "Overdue parties",
            "list_expiring_leases", "Leases ending soon",
            "list_missing_docs", "Missing documents",
            "list_expiring_docs", "Documents expiring soon",
            "completion_pct_by_party", "Document completion",
            "count_overdue_docs", "Overdue documents"
    );

    private static final Set<String> CHARGE_WIDGET_TYPES = Set.of(
            "count_overdue_charges",
            "sum_overdue_charges",
            "sum_expected_period",
            "sum_collected_period",
            "list_overdue_parties"
    );

    private static final Set<String> DOC_WIDGET_TYPES = Set.of(
            "list_missing_docs",
            "list_expiring_docs",
            "completion_pct_by_party",
            "count_overdue_docs"
    );

    private final AppStore appStore;
    private final AccessService accessService;
    private final DashboardStore dashboardStore;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final int statementTimeoutMs;

    public DashboardService(
            AppStore appStore,
            AccessService accessService,
            DashboardStore dashboardStore,
            Clock clock,
            @Value("${app.dashboard.statement-timeout-ms:5000}") int statementTimeoutMs
    ) {
        this.appStore = appStore;
        this.accessService = accessService;
        this.dashboardStore = dashboardStore;
        this.clock = clock;
        this.jsonMapper = JsonMapper.builder().build();
        this.statementTimeoutMs = statementTimeoutMs;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID workspaceId, UUID appId, UUID userId, String monthParam) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_APP);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new AppNotFoundException("Current version not found"));

        JsonNode def;
        try {
            def = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        String timezoneStr = def.path("timezone").asText("UTC");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezoneStr);
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }

        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        String currency = def.path("currency").asText("USD");

        YearMonth currentMonth = YearMonth.from(today);
        YearMonth selectedMonth;
        if (monthParam == null || monthParam.isBlank()) {
            selectedMonth = currentMonth;
        } else {
            if (!monthParam.matches("^\\d{4}-\\d{2}$")) {
                throw new AppBadRequestException("invalid month format");
            }
            try {
                selectedMonth = YearMonth.parse(monthParam);
            } catch (DateTimeParseException e) {
                throw new AppBadRequestException("invalid month format");
            }
            if (selectedMonth.isBefore(currentMonth.minusMonths(24)) || selectedMonth.isAfter(currentMonth.plusMonths(24))) {
                throw new AppBadRequestException("month out of range");
            }
        }

        LocalDate monthStart = selectedMonth.atDay(1);
        LocalDate monthEnd = selectedMonth.atEndOfMonth();

        try {
            dashboardStore.applyStatementTimeout(statementTimeoutMs);
            dashboardStore.beforeFirstWidgetQuery();

            List<DashboardResponse.WidgetResult> widgetResults = new ArrayList<>();
            JsonNode widgetsArray = def.path("widgets");
            if (widgetsArray.isArray()) {
                for (JsonNode w : widgetsArray) {
                    if (w.path("archived").asBoolean(false)) {
                        continue;
                    }
                    widgetResults.add(evaluateWidget(workspaceId, appId, def, w, today, selectedMonth, monthStart, monthEnd, currency));
                }
            }

            return new DashboardResponse(
                    ver.version(),
                    today,
                    selectedMonth.toString(),
                    Instant.now(clock),
                    widgetResults
            );
        } catch (DataAccessException e) {
            if (isTimeout(e)) {
                throw new DashboardTimeoutException("dashboard_timeout");
            }
            throw e;
        }
    }

    private DashboardResponse.WidgetResult evaluateWidget(
            UUID workspaceId,
            UUID appId,
            JsonNode def,
            JsonNode w,
            LocalDate today,
            YearMonth selectedMonth,
            LocalDate monthStart,
            LocalDate monthEnd,
            String currency
    ) {
        String id = w.path("id").asText();
        String type = w.path("type").asText();
        String label = WIDGET_LABELS.getOrDefault(type, type);
        String partyType = w.hasNonNull("partyType") ? w.get("partyType").asText() : null;

        // 1. Check PARTY_TYPE_MISSING
        if (partyType != null) {
            boolean partyTypeExists = false;
            JsonNode ptArray = def.path("partyTypes");
            if (ptArray.isArray()) {
                for (JsonNode pt : ptArray) {
                    if (partyType.equals(pt.path("id").asText()) && !pt.path("archived").asBoolean(false)) {
                        partyTypeExists = true;
                        break;
                    }
                }
            }
            if (!partyTypeExists) {
                return new DashboardResponse.WidgetResult(id, type, label, "UNAVAILABLE", "PARTY_TYPE_MISSING", null);
            }
        }

        // 2. Check BLOCK_MISSING
        if (CHARGE_WIDGET_TYPES.contains(type)) {
            boolean scheduleExists = false;
            JsonNode schedArray = def.path("chargeSchedules");
            if (schedArray.isArray()) {
                for (JsonNode s : schedArray) {
                    if (!s.path("archived").asBoolean(false)) {
                        if (partyType == null || partyType.equals(s.path("partyType").asText())) {
                            scheduleExists = true;
                            break;
                        }
                    }
                }
            }
            if (!scheduleExists) {
                return new DashboardResponse.WidgetResult(id, type, label, "UNAVAILABLE", "BLOCK_MISSING", null);
            }
        } else if (DOC_WIDGET_TYPES.contains(type)) {
            boolean checklistExists = false;
            JsonNode clArray = def.path("documentChecklists");
            if (clArray.isArray()) {
                for (JsonNode cl : clArray) {
                    if (!cl.path("archived").asBoolean(false)) {
                        if (partyType == null || partyType.equals(cl.path("partyType").asText())) {
                            checklistExists = true;
                            break;
                        }
                    }
                }
            }
            if (!checklistExists) {
                return new DashboardResponse.WidgetResult(id, type, label, "UNAVAILABLE", "BLOCK_MISSING", null);
            }
        }

        // 3. Check FIELD_MISSING (for list_expiring_leases)
        String dateField = null;
        if ("list_expiring_leases".equals(type)) {
            dateField = w.hasNonNull("dateField") ? w.get("dateField").asText() : null;
            if (dateField == null || dateField.isBlank()) {
                return new DashboardResponse.WidgetResult(id, type, label, "UNAVAILABLE", "FIELD_MISSING", null);
            }
            boolean fieldValid = false;
            JsonNode ptArray = def.path("partyTypes");
            if (ptArray.isArray()) {
                for (JsonNode pt : ptArray) {
                    if (!pt.path("archived").asBoolean(false)) {
                        if (partyType == null || partyType.equals(pt.path("id").asText())) {
                            JsonNode fields = pt.path("fields");
                            if (fields.isArray()) {
                                for (JsonNode f : fields) {
                                    if (dateField.equals(f.path("id").asText())
                                            && !f.path("archived").asBoolean(false)
                                            && "date".equals(f.path("type").asText())) {
                                        fieldValid = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (fieldValid) {
                        break;
                    }
                }
            }
            if (!fieldValid) {
                return new DashboardResponse.WidgetResult(id, type, label, "UNAVAILABLE", "FIELD_MISSING", null);
            }
        }

        // Widget is OK -> Compute data
        Object data = switch (type) {
            case "count_overdue_charges" -> new DashboardResponse.CountData(
                    dashboardStore.countOverdueCharges(workspaceId, appId, partyType, today)
            );
            case "sum_overdue_charges" -> new DashboardResponse.SumData(
                    dashboardStore.sumOverdueCharges(workspaceId, appId, partyType, today),
                    currency
            );
            case "sum_expected_period" -> new DashboardResponse.SumData(
                    dashboardStore.sumExpectedPeriod(workspaceId, appId, partyType, monthStart, monthEnd),
                    currency
            );
            case "sum_collected_period" -> new DashboardResponse.SumData(
                    dashboardStore.sumCollectedPeriod(workspaceId, appId, partyType, monthStart, monthEnd),
                    currency
            );
            case "list_overdue_parties" -> dashboardStore.listOverdueParties(
                    workspaceId, appId, partyType, today
            );
            case "list_expiring_leases" -> dashboardStore.listExpiringLeases(
                    workspaceId, appId, partyType, dateField, today, today.plusDays(60)
            );
            case "list_missing_docs" -> dashboardStore.listMissingDocs(
                    workspaceId, appId, partyType
            );
            case "list_expiring_docs" -> dashboardStore.listExpiringDocs(
                    workspaceId, appId, partyType, today, today.plusDays(30)
            );
            case "completion_pct_by_party" -> dashboardStore.completionPctByParty(
                    workspaceId, appId, partyType
            );
            case "count_overdue_docs" -> new DashboardResponse.CountData(
                    dashboardStore.countOverdueDocs(workspaceId, appId, partyType, today)
            );
            default -> null;
        };

        return new DashboardResponse.WidgetResult(id, type, label, "OK", null, data);
    }

    private boolean isTimeout(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof QueryTimeoutException) {
                return true;
            }
            if (curr instanceof SQLException sqle) {
                if ("57014".equals(sqle.getSQLState())) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }
}
