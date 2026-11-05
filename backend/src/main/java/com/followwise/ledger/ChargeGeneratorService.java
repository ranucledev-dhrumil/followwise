package com.followwise.ledger;

import com.followwise.app.AppDefinitionVersionRecord;
import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.identity.SystemUser;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChargeGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ChargeGeneratorService.class);

    private final LedgerStore ledgerStore;
    private final AppStore appStore;
    private final PartyStore partyStore;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    private final int catchupDays;
    private final int leadDays;
    private final int generationIntervalMinutes;
    private final int maxGeneratedPerAppRun;
    private final int maxLedgerEntriesPerApp;

    public ChargeGeneratorService(
            LedgerStore ledgerStore,
            AppStore appStore,
            PartyStore partyStore,
            Clock clock,
            @Value("${app.charge.catchup-days:35}") int catchupDays,
            @Value("${app.charge.lead-days:14}") int leadDays,
            @Value("${app.charge.generation-interval-minutes:60}") int generationIntervalMinutes,
            @Value("${app.charge.max-generated-per-app-run:500}") int maxGeneratedPerAppRun,
            @Value("${app.max-ledger-entries-per-app:20000}") int maxLedgerEntriesPerApp
    ) {
        this.ledgerStore = ledgerStore;
        this.appStore = appStore;
        this.partyStore = partyStore;
        this.clock = clock;
        this.jsonMapper = JsonMapper.builder().build();
        this.catchupDays = catchupDays;
        this.leadDays = leadDays;
        this.generationIntervalMinutes = generationIntervalMinutes;
        this.maxGeneratedPerAppRun = maxGeneratedPerAppRun;
        this.maxLedgerEntriesPerApp = maxLedgerEntriesPerApp;
    }

    @PostConstruct
    public void validateProperties() {
        if (generationIntervalMinutes < 5 || generationIntervalMinutes > 1440) {
            throw new IllegalStateException("app.charge.generation-interval-minutes must be between 5 and 1440");
        }
        if (catchupDays < 0 || catchupDays > 90) {
            throw new IllegalStateException("app.charge.catchup-days must be between 0 and 90");
        }
        if (leadDays < 0 || leadDays > 60) {
            throw new IllegalStateException("app.charge.lead-days must be between 0 and 60");
        }
        if (maxGeneratedPerAppRun < 1 || maxGeneratedPerAppRun > 2000) {
            throw new IllegalStateException("app.charge.max-generated-per-app-run must be between 1 and 2000");
        }
    }

    private boolean isIntegral(JsonNode node) {
        return node != null && !node.isNull() && !node.isFloatingPointNumber() && node.isIntegralNumber() && node.canConvertToLong();
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return jsonMapper.createObjectNode();
        }
        try {
            return jsonMapper.readTree(json);
        } catch (Exception e) {
            return jsonMapper.createObjectNode();
        }
    }

    @Transactional
    public ChargeGenerationResult generateForApp(
            UUID workspaceId,
            UUID appId,
            UUID callerUserId,
            boolean dryRun,
            boolean skipLocked
    ) {
        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new LedgerNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new LedgerConflictException("app_archived", "App is archived");
        }

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new LedgerNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse app definition", e);
        }

        ZoneId zoneId;
        try {
            String tz = defNode.path("timezone").asText(null);
            zoneId = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("UTC");
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }

        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        LocalDate windowStart = today.minusDays(catchupDays);
        LocalDate windowEnd = today.plusDays(leadDays);

        if (!dryRun) {
            if (skipLocked) {
                Optional<UUID> locked = appStore.lockAppRow(workspaceId, appId, true);
                if (locked.isEmpty()) {
                    return new ChargeGenerationResult(dryRun, 0, 0, 0, 1, 0, windowStart, windowEnd, today);
                }
            } else {
                appStore.lockAppRow(workspaceId, appId, false);
            }
        }

        long entryCount = ledgerStore.countTotal(workspaceId, appId);
        if (entryCount >= maxLedgerEntriesPerApp) {
            log.warn("App {} reached MAX_LEDGER_ENTRIES_PER_APP", appId);
            return new ChargeGenerationResult(dryRun, 0, 0, 0, 0, 0, windowStart, windowEnd, today);
        }

        JsonNode schedulesNode = defNode.path("chargeSchedules");
        if (!schedulesNode.isArray()) {
            return new ChargeGenerationResult(dryRun, 0, 0, 0, 0, 0, windowStart, windowEnd, today);
        }

        int created = 0;
        int skippedExisting = 0;
        int skippedInvalid = 0;
        int skippedLocked = 0;
        int failed = 0;

        for (JsonNode schedule : schedulesNode) {
            boolean enabled = schedule.path("enabled").asBoolean(true);
            boolean archived = schedule.path("archived").asBoolean(false);
            if (!enabled || archived) {
                continue;
            }

            String kind = schedule.path("kind").asText("");
            String partyType = schedule.path("partyType").asText("");
            String scheduleId = schedule.path("id").asText("");
            String scheduleLabel = schedule.path("label").asText("");

            if ("recurring".equals(kind)) {
                List<PartyRecord> parties = partyStore.findActiveByPartyType(workspaceId, appId, partyType);

                for (PartyRecord party : parties) {
                    if (entryCount + created >= maxLedgerEntriesPerApp) {
                        log.warn("App {} reached MAX_LEDGER_ENTRIES_PER_APP", appId);
                        break;
                    }
                    if (created >= maxGeneratedPerAppRun) {
                        break;
                    }

                    JsonNode partyDataNode = parseJson(party.dataJson());

                    String dueDaySource = schedule.path("dueDaySource").asText("field");
                    int dueDay = -1;
                    if ("fixed".equals(dueDaySource)) {
                        JsonNode ddNode = schedule.get("dueDay");
                        if (isIntegral(ddNode)) {
                            long d = ddNode.asLong();
                            if (d >= 1 && d <= 28) {
                                dueDay = (int) d;
                            }
                        }
                    } else {
                        String dueDayField = schedule.path("dueDayField").asText("");
                        JsonNode ddNode = partyDataNode.get(dueDayField);
                        if (isIntegral(ddNode)) {
                            long d = ddNode.asLong();
                            if (d >= 1 && d <= 28) {
                                dueDay = (int) d;
                            }
                        }
                    }

                    String amountSource = schedule.path("amountSource").asText("field");
                    long amountCents = -1;
                    if ("fixed".equals(amountSource)) {
                        JsonNode amtNode = schedule.get("amountCents");
                        if (isIntegral(amtNode)) {
                            long a = amtNode.asLong();
                            if (a >= 1 && a <= 100_000_000_000L) {
                                amountCents = a;
                            }
                        }
                    } else {
                        String amountField = schedule.path("amountField").asText("");
                        JsonNode amtNode = partyDataNode.get(amountField);
                        if (isIntegral(amtNode)) {
                            long a = amtNode.asLong();
                            if (a >= 1 && a <= 100_000_000_000L) {
                                amountCents = a;
                            }
                        }
                    }

                    if (dueDay == -1 || amountCents == -1) {
                        skippedInvalid++;
                        continue;
                    }

                    LocalDate partyCreatedAtDate = party.createdAt().atZone(zoneId).toLocalDate();

                    YearMonth currentYm = YearMonth.from(windowStart);
                    YearMonth endYm = YearMonth.from(windowEnd);

                    while (!currentYm.isAfter(endYm)) {
                        if (entryCount + created >= maxLedgerEntriesPerApp) {
                            log.warn("App {} reached MAX_LEDGER_ENTRIES_PER_APP", appId);
                            break;
                        }
                        if (created >= maxGeneratedPerAppRun) {
                            break;
                        }

                        LocalDate dueDate = currentYm.atDay(dueDay);
                        if (!dueDate.isBefore(windowStart) && !dueDate.isAfter(windowEnd) && !dueDate.isBefore(partyCreatedAtDate)) {
                            String periodKey = currentYm.toString();
                            String rawLabel = scheduleLabel + " " + periodKey;
                            String label = rawLabel.length() > 120 ? rawLabel.substring(0, 120) : rawLabel;

                            if (dryRun) {
                                boolean exists = ledgerStore.existsSchedulePeriodCharge(workspaceId, appId, party.id(), scheduleId, periodKey);
                                if (exists) {
                                    skippedExisting++;
                                } else {
                                    created++;
                                }
                            } else {
                                UUID entryId = UUID.randomUUID();
                                int affected = ledgerStore.insertEntryOnConflictDoNothing(
                                        entryId, workspaceId, appId, party.id(), entryId,
                                        "CHARGE", amountCents, "USD", "recurring",
                                        scheduleId, periodKey, label, dueDate,
                                        null, null, null, null, null, SystemUser.ID
                                );
                                if (affected > 0) {
                                    created++;
                                } else {
                                    skippedExisting++;
                                }
                            }
                        }
                        currentYm = currentYm.plusMonths(1);
                    }
                }
            } else if ("late_fee".equals(kind)) {
                int graceDays = schedule.path("graceDays").asInt(0);
                List<LedgerStore.OverdueChargeCandidate> candidates = ledgerStore.findOverdueChargesForLateFee(
                        workspaceId, appId, partyType, graceDays, today
                );

                for (LedgerStore.OverdueChargeCandidate candidate : candidates) {
                    if (entryCount + created >= maxLedgerEntriesPerApp) {
                        log.warn("App {} reached MAX_LEDGER_ENTRIES_PER_APP", appId);
                        break;
                    }
                    if (created >= maxGeneratedPerAppRun) {
                        break;
                    }

                    long amountCents = -1;
                    String amountSource = schedule.path("amountSource").asText("fixed");
                    if ("fixed".equals(amountSource)) {
                        JsonNode amtNode = schedule.get("amountCents");
                        if (isIntegral(amtNode)) {
                            long a = amtNode.asLong();
                            if (a >= 1 && a <= 100_000_000_000L) {
                                amountCents = a;
                            }
                        }
                    } else {
                        String amountField = schedule.path("amountField").asText("");
                        JsonNode partyDataNode = parseJson(candidate.partyDataJson());
                        JsonNode amtNode = partyDataNode.get(amountField);
                        if (isIntegral(amtNode)) {
                            long a = amtNode.asLong();
                            if (a >= 1 && a <= 100_000_000_000L) {
                                amountCents = a;
                            }
                        }
                    }

                    if (amountCents == -1) {
                        skippedInvalid++;
                        continue;
                    }

                    String periodKey = candidate.chargeId().toString();
                    String rawLabel = "Late fee " + candidate.label();
                    String label = rawLabel.length() > 120 ? rawLabel.substring(0, 120) : rawLabel;

                    if (dryRun) {
                        boolean exists = ledgerStore.existsSchedulePeriodCharge(workspaceId, appId, candidate.partyId(), scheduleId, periodKey);
                        if (exists) {
                            skippedExisting++;
                        } else {
                            created++;
                        }
                    } else {
                        UUID entryId = UUID.randomUUID();
                        int affected = ledgerStore.insertEntryOnConflictDoNothing(
                                entryId, workspaceId, appId, candidate.partyId(), entryId,
                                "CHARGE", amountCents, "USD", "late_fee",
                                scheduleId, periodKey, label, today,
                                null, null, null, null, null, SystemUser.ID
                        );
                        if (affected > 0) {
                            created++;
                        } else {
                            skippedExisting++;
                        }
                    }
                }
            }
        }

        log.info("Charge generation for app {}: created={}, skippedExisting={}, skippedInvalid={}, skippedLocked={}, failed={}",
                appId, created, skippedExisting, skippedInvalid, skippedLocked, failed);

        return new ChargeGenerationResult(dryRun, created, skippedExisting, skippedInvalid, skippedLocked, failed, windowStart, windowEnd, today);
    }
}
