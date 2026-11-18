package com.followwise.reminder;

import com.followwise.app.AppDefinitionVersionRecord;
import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.document.DocumentRequestStore;
import com.followwise.ledger.LedgerStore;
import com.followwise.party.PartyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ReminderEngineService {

    private static final Logger log = LoggerFactory.getLogger(ReminderEngineService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private final ReminderEventStore reminderEventStore;
    private final ReminderProperties reminderProperties;
    private final AppStore appStore;
    private final PartyStore partyStore;
    private final LedgerStore ledgerStore;
    private final DocumentRequestStore documentRequestStore;
    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    public record ReminderPreviewItem(
            String ruleId,
            UUID partyId,
            String partyDisplayName,
            UUID subjectId,
            String messageKey,
            LocalDate scheduledFor,
            String recipientKind,
            UUID recipientUserId,
            String skipReason
    ) {}

    public record ReminderProcessResult(
            boolean dryRun,
            boolean remindersEnabled,
            boolean blockedByBacklog,
            boolean capReached,
            boolean scanLimitReached,
            int queued,
            int skippedUnverifiedOwner,
            int skippedNoEmail,
            int skippedPaused,
            int skippedRateLimit,
            int skippedExisting,
            int skippedLocked,
            int failed,
            List<ReminderPreviewItem> previewItems
    ) {}

    private record Candidate(
            String ruleId,
            String partyType,
            String messageKey,
            String recipientType,
            UUID partyId,
            String partyDisplayName,
            String partyDataJson,
            boolean partyPaused,
            UUID subjectId,
            String subjectLabel,
            long amountCents,
            LocalDate targetDate,
            LocalDate scheduledFor
    ) {}

    public ReminderEngineService(
            ReminderEventStore reminderEventStore,
            ReminderProperties reminderProperties,
            AppStore appStore,
            PartyStore partyStore,
            LedgerStore ledgerStore,
            DocumentRequestStore documentRequestStore,
            JdbcClient jdbcClient,
            Clock clock
    ) {
        this.reminderEventStore = reminderEventStore;
        this.reminderProperties = reminderProperties;
        this.appStore = appStore;
        this.partyStore = partyStore;
        this.ledgerStore = ledgerStore;
        this.documentRequestStore = documentRequestStore;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Transactional
    public ReminderProcessResult processApp(UUID workspaceId, UUID appId, boolean dryRun, boolean skipLocked) {
        long queuedOutbox = reminderEventStore.countQueuedOutboxTotal();
        boolean blockedByBacklog = queuedOutbox > reminderProperties.getMaxOutboxBacklog();

        if (!dryRun) {
            if (!reminderProperties.isRemindersEnabled()) {
                return new ReminderProcessResult(false, false, blockedByBacklog, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
            }

            if (blockedByBacklog) {
                log.warn("Reminder run blocked by outbox backlog: queued={}", queuedOutbox);
                return new ReminderProcessResult(false, true, true, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
            }

            if (skipLocked) {
                Optional<UUID> locked = appStore.lockAppRow(workspaceId, appId, true);
                if (locked.isEmpty()) {
                    return new ReminderProcessResult(false, true, false, false, false, 0, 0, 0, 0, 0, 0, 1, 0, List.of());
                }
            } else {
                appStore.lockAppRow(workspaceId, appId, false);
            }
        }

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new ReminderNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new ReminderConflictException("app_archived", "App is archived");
        }

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new ReminderNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse app definition", e);
        }

        String tzStr = defNode.path("timezone").asText("UTC");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tzStr);
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }

        ZonedDateTime nowZdt = clock.instant().atZone(zoneId);
        LocalDate today = nowZdt.toLocalDate();
        int localHour = nowZdt.getHour();

        if (localHour < reminderProperties.getSendHour()) {
            return new ReminderProcessResult(dryRun, reminderProperties.isRemindersEnabled(), blockedByBacklog, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        LocalDate windowStart = today.minusDays(reminderProperties.getCatchupDays());
        LocalDate windowEnd = today;

        List<PartyStore.OwnerEmail> verifiedOwners = partyStore.findOwnersWithVerifiedEmail(workspaceId);
        boolean hasVerifiedOwner = !verifiedOwners.isEmpty();
        String earliestOwnerEmail = hasVerifiedOwner ? verifiedOwners.get(0).email() : null;

        String workspaceName = jdbcClient.sql("SELECT name FROM workspaces WHERE id = :wid")
                .param("wid", workspaceId)
                .query(String.class)
                .optional()
                .orElse("Workspace");
        String appName = app.name();

        JsonNode rulesNode = defNode.path("reminderRules");
        if (!rulesNode.isArray()) {
            return new ReminderProcessResult(dryRun, reminderProperties.isRemindersEnabled(), blockedByBacklog, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        int runCap = reminderProperties.getMaxRemindersPerAppRun();
        int maxScannedPerRule = reminderProperties.getMaxCandidatesScannedPerRule();
        int pageSize = reminderProperties.getPageSize();

        int queued = 0;
        int skippedUnverifiedOwner = 0;
        int skippedNoEmail = 0;
        int skippedPaused = 0;
        int skippedRateLimit = 0;
        int skippedExisting = 0;
        Instant now = clock.instant();

        List<ReminderPreviewItem> previewItems = new ArrayList<>();
        boolean capReached = false;
        boolean scanLimitReachedAnyRule = false;
        boolean[] loggedCap = new boolean[]{false};

        record Target(String recipientKind, UUID recipientUserId, String toAddress) {}

        for (JsonNode rule : rulesNode) {
            if (capReached) {
                break;
            }
            boolean enabled = rule.path("enabled").asBoolean(true);
            boolean archived = rule.path("archived").asBoolean(false);
            if (!enabled || archived) {
                continue;
            }

            String ruleId = rule.path("id").asText("");
            String partyType = rule.path("partyType").asText("");
            String trigger = rule.path("trigger").asText("");
            int offsetDays = rule.path("offsetDays").asInt(0);
            String recipient = rule.path("recipient").asText("party");
            String messageKey = rule.path("messageKey").asText("");
            String dateField = rule.path("dateField").asText("");

            LocalDate minDate = today.minusDays(reminderProperties.getCatchupDays() + offsetDays);
            LocalDate maxDate = today.minusDays(offsetDays);

            LocalDate cursorDate = null;
            UUID cursorPartyId = null;
            UUID cursorSubjectId = null;
            int scannedThisRule = 0;
            boolean scanLimitReachedThisRule = false;

            while (true) {
                if (capReached) {
                    break;
                }

                List<Candidate> page = fetchCandidatePage(
                        workspaceId, appId, partyType, trigger, dateField,
                        minDate, maxDate, offsetDays, ruleId, messageKey, recipient,
                        cursorDate, cursorPartyId, cursorSubjectId, pageSize
                );

                if (page.isEmpty()) {
                    break;
                }

                for (Candidate cand : page) {
                    cursorDate = cand.targetDate();
                    cursorPartyId = cand.partyId();
                    cursorSubjectId = cand.subjectId();

                    if (scannedThisRule >= maxScannedPerRule) {
                        scanLimitReachedThisRule = true;
                        scanLimitReachedAnyRule = true;
                        log.warn("Rule {} reached scan limit of {} candidates in app {}", ruleId, maxScannedPerRule, appId);
                        break;
                    }
                    scannedThisRule++;

                    if (cand.scheduledFor().isBefore(windowStart) || cand.scheduledFor().isAfter(windowEnd)) {
                        continue;
                    }

                    List<Target> targets = new ArrayList<>();
                    if ("party".equalsIgnoreCase(cand.recipientType())) {
                        String partyEmail = resolvePartyEmail(defNode, cand.partyType(), cand.partyDataJson());
                        targets.add(new Target("PARTY", null, partyEmail));
                    } else {
                        if (verifiedOwners.isEmpty()) {
                            targets.add(new Target("OWNER", null, null));
                        } else {
                            for (var owner : verifiedOwners) {
                                targets.add(new Target("OWNER", owner.userId(), owner.email()));
                            }
                        }
                    }

                    for (Target target : targets) {
                        // Check idempotency first: candidates that already have an event are skipped without counting toward the cap
                        boolean alreadyExists = reminderEventStore.existsEvent(
                                appId, cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor()
                        );
                        if (alreadyExists) {
                            skippedExisting++;
                            continue;
                        }

                        // Check cap immediately before write
                        if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                            capReached = true;
                            break;
                        }

                        // Gate 1: UNVERIFIED_OWNER
                        if (!hasVerifiedOwner) {
                            if (dryRun) {
                                if (previewItems.size() < 200) {
                                    previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), "UNVERIFIED_OWNER"));
                                }
                                skippedUnverifiedOwner++;
                            } else {
                                UUID eventId = UUID.randomUUID();
                                int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "SKIPPED", "UNVERIFIED_OWNER", null, cand.messageKey(), now);
                                if (inserted > 0) {
                                    skippedUnverifiedOwner++;
                                } else {
                                    skippedExisting++;
                                }
                            }
                            if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                                capReached = true;
                                break;
                            }
                            continue;
                        }

                        // Gate 2: NO_EMAIL
                        if ("PARTY".equals(target.recipientKind())) {
                            if (target.toAddress() == null || !isValidEmail(target.toAddress())) {
                                if (dryRun) {
                                    if (previewItems.size() < 200) {
                                        previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), "NO_EMAIL"));
                                    }
                                    skippedNoEmail++;
                                } else {
                                    UUID eventId = UUID.randomUUID();
                                    int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "SKIPPED", "NO_EMAIL", null, cand.messageKey(), now);
                                    if (inserted > 0) {
                                        skippedNoEmail++;
                                    } else {
                                        skippedExisting++;
                                    }
                                }
                                if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                                    capReached = true;
                                    break;
                                }
                                continue;
                            }
                        }

                        // Gate 3: PAUSED
                        if (cand.partyPaused()) {
                            if (dryRun) {
                                if (previewItems.size() < 200) {
                                    previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), "PAUSED"));
                                }
                                skippedPaused++;
                            } else {
                                UUID eventId = UUID.randomUUID();
                                int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "SKIPPED", "PAUSED", null, cand.messageKey(), now);
                                if (inserted > 0) {
                                    skippedPaused++;
                                } else {
                                    skippedExisting++;
                                }
                            }
                            if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                                capReached = true;
                                break;
                            }
                            continue;
                        }

                        // Gate 4: RATE_LIMIT
                        if ("PARTY".equals(target.recipientKind())) {
                            long partyQueuedToday = reminderEventStore.countQueuedPartyToday(appId, cand.partyId(), today, tzStr);
                            if (partyQueuedToday >= reminderProperties.getMaxRemindersPerPartyPerDay()) {
                                if (dryRun) {
                                    if (previewItems.size() < 200) {
                                        previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), "RATE_LIMIT"));
                                    }
                                    skippedRateLimit++;
                                } else {
                                    UUID eventId = UUID.randomUUID();
                                    int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "SKIPPED", "RATE_LIMIT", null, cand.messageKey(), now);
                                    if (inserted > 0) {
                                        skippedRateLimit++;
                                    } else {
                                        skippedExisting++;
                                    }
                                }
                                if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                                    capReached = true;
                                    break;
                                }
                                continue;
                            }
                        } else {
                            long ownerQueuedToday = reminderEventStore.countQueuedOwnerToday(appId, target.recipientUserId(), today, tzStr);
                            if (ownerQueuedToday >= reminderProperties.getMaxRemindersPerOwnerPerDay()) {
                                if (dryRun) {
                                    if (previewItems.size() < 200) {
                                        previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), "RATE_LIMIT"));
                                    }
                                    skippedRateLimit++;
                                } else {
                                    UUID eventId = UUID.randomUUID();
                                    int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "SKIPPED", "RATE_LIMIT", null, cand.messageKey(), now);
                                    if (inserted > 0) {
                                        skippedRateLimit++;
                                    } else {
                                        skippedExisting++;
                                    }
                                }
                                if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                                    capReached = true;
                                    break;
                                }
                                continue;
                            }
                        }

                        // Render content
                        Map<String, String> vars = new HashMap<>();
                        vars.put("party", cand.partyDisplayName());
                        vars.put("workspace", workspaceName);
                        vars.put("app", appName);
                        vars.put("label", cand.subjectLabel());
                        vars.put("amount", MessageTemplates.formatAmount(cand.amountCents()));
                        vars.put("due", cand.targetDate() != null ? cand.targetDate().toString() : "");
                        vars.put("expiry", cand.targetDate() != null ? cand.targetDate().toString() : "");
                        long days = cand.targetDate() != null ? Math.abs(ChronoUnit.DAYS.between(today, cand.targetDate())) : 0;
                        vars.put("days", String.valueOf(days));
                        vars.put("item", cand.subjectLabel());

                        MessageTemplates.RenderedMessage rendered = MessageTemplates.render(cand.messageKey(), vars);

                        if (dryRun) {
                            if (previewItems.size() < 200) {
                                previewItems.add(new ReminderPreviewItem(cand.ruleId(), cand.partyId(), cand.partyDisplayName(), cand.subjectId(), cand.messageKey(), cand.scheduledFor(), target.recipientKind(), target.recipientUserId(), null));
                            }
                            queued++;
                        } else {
                            UUID outboxId = UUID.randomUUID();
                            UUID eventId = UUID.randomUUID();
                            String replyTo = "PARTY".equals(target.recipientKind()) ? earliestOwnerEmail : null;
                            reminderEventStore.insertOutboxReminder(outboxId, target.toAddress(), rendered.subject(), rendered.bodyText(), replyTo, now);
                            int inserted = reminderEventStore.insertEvent(eventId, workspaceId, appId, cand.partyId(), cand.ruleId(), cand.subjectId(), target.recipientKind(), target.recipientUserId(), cand.scheduledFor(), "QUEUED", null, outboxId, cand.messageKey(), now);
                            if (inserted > 0) {
                                queued++;
                            } else {
                                skippedExisting++;
                            }
                        }

                        if (checkCap(appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, runCap, loggedCap)) {
                            capReached = true;
                            break;
                        }
                    }

                    if (capReached || scanLimitReachedThisRule) {
                        break;
                    }
                }

                if (page.size() < pageSize || capReached || scanLimitReachedThisRule) {
                    break;
                }
            }
        }

        if (dryRun) {
            previewItems.sort(
                    Comparator.comparing(ReminderPreviewItem::scheduledFor)
                            .thenComparing(item -> item.partyId() != null ? item.partyId() : new UUID(0, 0))
                            .thenComparing(item -> item.subjectId() != null ? item.subjectId() : new UUID(0, 0))
                            .thenComparing(ReminderPreviewItem::recipientKind)
            );
            if (previewItems.size() > 200) {
                previewItems = new ArrayList<>(previewItems.subList(0, 200));
            }
        }

        log.info("Reminder processing for app {}: queued={}, skippedUnverifiedOwner={}, skippedNoEmail={}, skippedPaused={}, skippedRateLimit={}, skippedExisting={}",
                appId, queued, skippedUnverifiedOwner, skippedNoEmail, skippedPaused, skippedRateLimit, skippedExisting);

        return new ReminderProcessResult(
                dryRun,
                reminderProperties.isRemindersEnabled(),
                blockedByBacklog,
                capReached,
                scanLimitReachedAnyRule,
                queued,
                skippedUnverifiedOwner,
                skippedNoEmail,
                skippedPaused,
                skippedRateLimit,
                skippedExisting,
                0,
                0,
                previewItems
        );
    }

    private boolean checkCap(UUID appId, int queued, int skippedUnverifiedOwner, int skippedNoEmail, int skippedPaused, int skippedRateLimit, int runCap, boolean[] loggedCap) {
        int totalWritten = queued + skippedUnverifiedOwner + skippedNoEmail + skippedPaused + skippedRateLimit;
        if (totalWritten >= runCap) {
            if (!loggedCap[0]) {
                log.warn("App {} reached MAX_REMINDERS_PER_APP_RUN ({})", appId, runCap);
                loggedCap[0] = true;
            }
            return true;
        }
        return false;
    }

    private List<Candidate> fetchCandidatePage(
            UUID workspaceId, UUID appId, String partyType, String trigger, String dateField,
            LocalDate minDate, LocalDate maxDate, int offsetDays, String ruleId, String messageKey, String recipient,
            LocalDate cursorDate, UUID cursorPartyId, UUID cursorSubjectId, int limit
    ) {
        List<Candidate> list = new ArrayList<>();
        if ("charge.due".equals(trigger) || "charge.overdue".equals(trigger)) {
            var charges = ledgerStore.findChargesForReminder(workspaceId, appId, partyType, minDate, maxDate, cursorDate, cursorPartyId, cursorSubjectId, limit);
            for (var c : charges) {
                LocalDate sched = c.dueDate().plusDays(offsetDays);
                list.add(new Candidate(ruleId, partyType, messageKey, recipient, c.partyId(), c.partyDisplayName(), c.partyDataJson(), c.remindersPaused(), c.chargeId(), c.label(), c.outstandingCents(), c.dueDate(), sched));
            }
        } else if ("document.due".equals(trigger)) {
            var reqs = documentRequestStore.findDueRequestsForReminder(workspaceId, appId, partyType, minDate, maxDate, cursorDate, cursorPartyId, cursorSubjectId, limit);
            for (var r : reqs) {
                LocalDate sched = r.dueDate().plusDays(offsetDays);
                list.add(new Candidate(ruleId, partyType, messageKey, recipient, r.partyId(), r.partyDisplayName(), r.partyDataJson(), r.remindersPaused(), r.requestId(), r.label(), 0L, r.dueDate(), sched));
            }
        } else if ("document.expiry".equals(trigger)) {
            var reqs = documentRequestStore.findExpiringRequestsForReminder(workspaceId, appId, partyType, minDate, maxDate, cursorDate, cursorPartyId, cursorSubjectId, limit);
            for (var r : reqs) {
                LocalDate sched = r.expiresOn().plusDays(offsetDays);
                list.add(new Candidate(ruleId, partyType, messageKey, recipient, r.partyId(), r.partyDisplayName(), r.partyDataJson(), r.remindersPaused(), r.requestId(), r.label(), 0L, r.expiresOn(), sched));
            }
        } else if ("lease.end".equals(trigger)) {
            var parties = partyStore.findPartiesForDateReminder(workspaceId, appId, partyType, dateField, minDate, maxDate, cursorDate, cursorPartyId, cursorSubjectId, limit);
            for (var p : parties) {
                LocalDate sched = p.targetDate().plusDays(offsetDays);
                list.add(new Candidate(ruleId, partyType, messageKey, recipient, p.partyId(), p.displayName(), p.partyDataJson(), p.remindersPaused(), p.partyId(), "", 0L, p.targetDate(), sched));
            }
        }
        return list;
    }

    private String resolvePartyEmail(JsonNode defNode, String partyType, String partyDataJson) {
        String emailFieldId = null;
        JsonNode partyTypesNode = defNode.path("partyTypes");
        if (partyTypesNode.isArray()) {
            for (JsonNode pt : partyTypesNode) {
                if (partyType.equals(pt.path("id").asText(""))) {
                    JsonNode fields = pt.path("fields");
                    if (fields.isArray()) {
                        for (JsonNode f : fields) {
                            if (!f.path("archived").asBoolean(false) && "email".equals(f.path("type").asText(""))) {
                                emailFieldId = f.path("id").asText("");
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (emailFieldId == null || emailFieldId.isBlank()) {
            return null;
        }

        try {
            JsonNode dataNode = jsonMapper.readTree(partyDataJson);
            if (dataNode.hasNonNull(emailFieldId) && dataNode.get(emailFieldId).isTextual()) {
                String email = dataNode.get(emailFieldId).asText().trim();
                return email.isEmpty() ? null : email;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        return !trimmed.contains("\r") && !trimmed.contains("\n") && trimmed.length() <= 254 && EMAIL_PATTERN.matcher(trimmed).matches();
    }
}
