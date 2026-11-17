# PLAN-0014: Reminder engine (Phase 1, step 1f)

Status: DRAFT        <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 1, step 1f
Author: Claude Sonnet 4.6   Executor: (model that executed)
Branch: plan-0014 (created by the founder; AI models run no git)

## Goal
A scheduled, idempotent engine that turns each app's reminderRules into email_outbox rows, plus preview, pause, resume, and history endpoints. It never sends mail itself. Also fixes the getDocumentDownload connection leak from Slice 0.

## Out of scope
Actual email sending, portal links, antivirus, retention jobs for reminder_events/outbox rows, SMS, frontend.

## Inputs
README sections 6, 7.3, 8.5, 11, 14.2; D-47 to D-50, D-62 to D-66, D-84, D-87 to D-93, D-97 to D-100 (already in README), D-101 to D-108 (appended by executor); V4, V7, V9 migrations; outbox/, email/SmtpEmailSender.java, email/EmailSender.java; ledger/ChargeGeneratorService.java, ChargeGenerationScheduler.java; document/DocumentService.java (getDocumentDownload), DocumentRequestStore.java; party/PartyStore.java, app/AppStore.java; workspace/Permission.java; identity/User.java; MembershipRepository.java; TestDbHelper.java; app-definition-v1.schema.json (reminderRule section); both templates; this plan.

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| db/migration/V10__reminders.sql | create | reminder_events table, parties.reminders_paused_at, email_outbox.reply_to, immutability trigger |
| reminder/ReminderEventStore.java | create | JdbcClient queries for reminder_events |
| reminder/ReminderEngineService.java | create | core engine: dry-run + write; shared by scheduler and preview endpoint |
| reminder/ReminderScheduler.java | create | @Scheduled, ConditionalOnProperty, per-app transaction loop |
| reminder/ReminderController.java | create | preview, pause, resume, history endpoints |
| reminder/ReminderExceptionHandler.java | create | problem+json for reminder errors |
| reminder/ReminderProperties.java | create | @PostConstruct startup validation |
| reminder/MessageTemplates.java | create | 10 plain-text templates; sanitize() helper |
| outbox/OutboxService.java | change | read reply_to from EmailOutbox; pass to emailSender if present |
| outbox/EmailOutbox.java | change | add replyTo field |
| email/EmailSender.java | change | add send(to, subject, body, replyTo) overload |
| email/SmtpEmailSender.java | change | implement new overload; use per-message reply_to when not blank, else static replyTo |
| workspace/Permission.java | change | add VIEW_REMINDERS (OWNER, STAFF) |
| party/PartyRecord.java | change | add remindersPausedAt field |
| party/PartyStore.java | change | add remindersPausedAt to mapper; add pauseReminders/resumeReminders methods |
| document/DocumentService.java | change | Slice 0: decouple getDocumentDownload from transaction |
| TestDbHelper.java | change | DELETE FROM reminder_events before parties |
| .env.example | change | add REMINDERS_ENABLED, numeric settings with defaults |

## Steps

### Slice 0: fix getDocumentDownload connection leak
1. In `DocumentService.java`, extract the lookup (permission check + documentStore.findById + purge check + storageKey resolution) into a private `@Transactional(readOnly=true)` helper method that returns a record `(storageKey, originalFilename, contentType, sizeBytes)`. Remove `@Transactional(readOnly=true)` from the public `getDocumentDownload` method. Call the helper first (commits), then call `documentStorage.open(storageKey)` outside any transaction. Return `DocumentDownload` as before.
2. Add tests in `DocumentApiTest` (or a new `DocumentDownloadConcurrencyTest`): while a fake `documentStorage.open` is blocked on a latch, assert (a) `SELECT id FROM apps WHERE id = ? FOR UPDATE NOWAIT` succeeds from a second connection (no held row lock), and (b) a second download request for a different document completes normally.
3. Check: `cd backend && mvn test` green.

### Slice 1: schema (V10) + model changes
1. Write `V10__reminders.sql`:
   - `ALTER TABLE parties ADD COLUMN reminders_paused_at TIMESTAMPTZ NULL;`
   - `ALTER TABLE email_outbox ADD COLUMN reply_to TEXT NULL;`
   - `CREATE TABLE reminder_events (id uuid PK, workspace_id, app_id, party_id, rule_id text, subject_id uuid null, recipient_kind text CHECK IN ('PARTY','OWNER'), recipient_user_id uuid null, scheduled_for date NOT NULL, status text CHECK IN ('QUEUED','SKIPPED'), skip_reason text null, outbox_id uuid null REFERENCES email_outbox(id) ON DELETE RESTRICT, message_key text NOT NULL, created_at timestamptz default clock_timestamp(), CONSTRAINT fk_re_party FOREIGN KEY (party_id, workspace_id, app_id) REFERENCES parties(id, workspace_id, app_id) ON DELETE RESTRICT, CONSTRAINT chk_re_skip CHECK (skip_reason IS NOT NULL) = (status = 'SKIPPED'), CONSTRAINT chk_re_outbox CHECK (outbox_id IS NOT NULL) = (status = 'QUEUED'));`
   - Unique index on `(app_id, rule_id, coalesce(subject_id,'00000000-0000-0000-0000-000000000000'), recipient_kind, coalesce(recipient_user_id,'00000000-0000-0000-0000-000000000000'), scheduled_for)`.
   - Immutability trigger: function blocks UPDATE, DELETE, TRUNCATE on reminder_events (same pattern as documents trigger).
   - Index on `(app_id, party_id, status)`.
2. Update `EmailOutbox.java`: add `replyTo` field + getter/setter.
3. Update `EmailSender.java`: add `default void send(String to, String subject, String body, String replyTo) { send(to, subject, body); }`.
4. Update `SmtpEmailSender.java`: implement the 4-arg overload; use `replyTo` param if not blank, else fall back to `this.replyTo`.
5. Update `OutboxService.java`: call `emailSender.send(item.getToAddress(), item.getSubject(), item.getBodyText(), item.getReplyTo())`.
6. Update `PartyRecord.java`: add `Instant remindersPausedAt` field; add `isPaused()` boolean.
7. Update `PartyStore.java`: add column to mapper; add `pauseReminders(workspaceId, appId, partyId, now)` and `resumeReminders(workspaceId, appId, partyId)` (conditional UPDATE bumping version + updated_at per D-77 pattern); add `findOwnersWithVerifiedEmail(workspaceId)` returning `List<OwnerEmail>` record `(UUID userId, String email)` ordered by created_at, limit 5.
8. Update `Permission.java`: add `VIEW_REMINDERS(Set.of(Role.OWNER, Role.STAFF))`.
9. Update `TestDbHelper.java`: add `DELETE FROM reminder_events;` before `DELETE FROM document_requests;`.
10. Add env vars to `.env.example`: `REMINDERS_ENABLED=false`, `REMINDER_CATCHUP_DAYS=2`, `REMINDER_SEND_HOUR=9`, `MAX_REMINDERS_PER_PARTY_PER_DAY=2`, `MAX_REMINDERS_PER_APP_RUN=100`, `MAX_OUTBOX_BACKLOG=200`, `REMINDER_INTERVAL_MINUTES=15`.
11. Check: `cd backend && mvn test` green (migration applies, existing tests pass).

### Slice 2: ReminderProperties, MessageTemplates, ReminderEventStore
1. `ReminderProperties.java`: reads all numeric settings via `@Value`; `@PostConstruct` validates ranges with fixed-text `IllegalStateException` messages (interval 5–120, catchup 0–7, hour 0–23, dailyCap 1–20, runCap 1–1000, backlog 1–10000).
2. `MessageTemplates.java`: static `sanitize(String s)` strips chars < 0x20, 0x7F, `<`, `>`, truncates to 120 chars. Static method `render(String messageKey, Map<String,String> vars)` selects one of 10 plain-text templates by key (charge_reminder_before, charge_reminder_due, charge_reminder_late, charge_overdue_owner, doc_reminder_before, doc_reminder_due, doc_reminder_late, doc_expiry_reminder, doc_overdue_owner, lease_ending_owner), substitutes vars (all pre-sanitized), returns `{subject, body}` record. Footer appended to every body. Throws `IllegalArgumentException` on unknown key.
3. `ReminderEventStore.java` (JdbcClient, no JPA): `insertEvent(...)`, `countQueuedToday(workspaceId, appId, partyId, appLocalToday)` for rate-limit check, `countQueuedOutbox(workspaceId)` for backlog guard, `findHistory(workspaceId, appId, partyId, status, page, size)`, `existsEvent(appId, ruleId, subjectId, recipientKind, recipientUserId, scheduledFor)` for idempotency check.
4. Check: `cd backend && mvn test` green.

### Slice 3: engine + scheduler + endpoints
1. `ReminderEngineService.java`: method `processApp(workspaceId, appId, dryRun, skipLocked)` annotated `@Transactional`:
   - Lock app row (`FOR UPDATE SKIP LOCKED` when skipLocked); skip if empty.
   - Check REMINDERS_ENABLED; check backlog guard (count queued outbox rows, skip whole run if > MAX_OUTBOX_BACKLOG).
   - Load current app definition; compute today and local hour in app timezone.
   - Check send-hour gate: skip app if local hour < REMINDER_SEND_HOUR.
   - Load owner emails: `findOwnersWithVerifiedEmail`; if none → SKIPPED UNVERIFIED_OWNER per rule (log count, continue to next rule; no event row for rules whose workspace has no verified owner).
   - For each enabled, non-archived reminderRule: dispatch to private method by trigger type (charge.due, charge.overdue, document.due, document.expiry, lease.end). Each candidate computes `scheduledFor = dateField + offsetDays`. Include only candidates where `scheduledFor` is in `[today - CATCHUP_DAYS, today]`. For each candidate:
     - Skip if subject state check fails (charge paid/reversed, request not in expected status, party archived) — no event row.
     - Determine recipient address(es): PARTY → first email field in party type definition order; missing/invalid → insert SKIPPED NO_EMAIL event and continue. OWNER → up to 5 verified owners.
     - Check party not archived and `reminders_paused_at` null; if paused → SKIPPED PAUSED.
     - Check daily cap; if over → SKIPPED RATE_LIMIT.
     - Check per-run cap; if over → stop this rule.
     - Idempotency: `existsEvent(...)` → skip silently if already exists.
     - Build message via `MessageTemplates.render`. Sanitize all variables before passing.
     - If not dryRun: insert email_outbox row (kind REMINDER, reply_to = earliest verified owner email, send_after = now); insert QUEUED reminder_event row with outbox_id. Both inserts in same transaction (already in app-locked transaction).
     - If dryRun: collect preview items (never email addresses); cap at 200.
   - Log only counts: apps processed, queued, skipped by reason, failed.
2. `ReminderScheduler.java`: `@Component`, `@ConditionalOnProperty("app.scheduler-enabled")`, `@Scheduled(fixedDelayString = "#{...REMINDER_INTERVAL_MINUTES * 60000}", initialDelayString = random 0–30s). Calls `appStore.findActiveAppIds()`, iterates, calls `reminderEngineService.processApp(..., skipLocked=true)` per app in try/catch; logs exception class name and app id only.
3. `ReminderController.java`: under `/api/workspaces/{wid}/apps/{aid}`:
   - `GET .../reminders/preview` (VIEW_REMINDERS): calls `processApp(..., dryRun=true)`; returns list capped at 200; no addresses in response.
   - `POST .../parties/{partyId}/reminders/pause` (EDIT_DATA): lock app → lock party; check not archived; check app not archived; bump party version + set reminders_paused_at = now; return party response.
   - `POST .../parties/{partyId}/reminders/resume` (EDIT_DATA): same locks; clear reminders_paused_at; bump version; return party response. Both accept `{expectedVersion}` body for optimistic lock; 409 stale_version on mismatch.
   - `GET .../reminder-events` (VIEW_REMINDERS): params partyId, status, page (default 0), size (default 20, max 50); no body or address fields in response; ordered by created_at desc, id desc.
4. `ReminderExceptionHandler.java`: problem+json for 400/403/404/409.
5. Check: `cd backend && mvn test` green.

### Slice 4: tests + docs
1. Write `ReminderApiTest` (Testcontainers, mutable Clock, FakeEmailSender or mocked SmtpEmailSender — no real addresses):
   - For each of 5 triggers: offsetDays −1, 0, +1; verify exact scheduledFor and event creation on the correct day only.
   - Catch-up: missed day inside window created; day before window not created.
   - Send-hour gate: app in UTC+14 and UTC−12 across date line; hour before threshold → no event; at threshold → event.
   - Idempotency: second run creates no new rows; two concurrent runs (latched) create no duplicates (unique index holds).
   - State recheck: paid charge, partly paid charge, reversed charge, received request, approved request, cancelled request, archived party → no event.
   - Late-fee charges reminded like recurring.
   - Each skip reason (UNVERIFIED_OWNER, NO_EMAIL, PAUSED, RATE_LIMIT) → correct event row with skip_reason.
   - REMINDERS_ENABLED=false → engine does nothing.
   - Per-run cap: creates exactly MAX_REMINDERS_PER_APP_RUN events then stops.
   - Outbox backlog guard: inject MAX_OUTBOX_BACKLOG+1 queued rows → engine skips.
   - Owner recipients: 3 owners (1 unverified) → 2 events, unverified excluded; 6 owners → 5 events (cap).
   - Reply-To: earliest verified owner email appears in outbox row's reply_to; SmtpEmailSender mock receives it in the 4-arg call.
   - Templates: all 10 messageKeys render; party name with `<`, `>`, CRLF, SECRET_MARKER_123 is sanitized in body/subject; no other party's data included.
   - reminder_events never contain email address in any column.
   - OutboxService sends REMINDER rows after account rows (priority ordering via send_after: account rows get send_after=now, reminder rows get send_after=now+1s; or query orders by kind; verify existing OutboxService ordering behavior).
   - Preview returns same items as a dry-run, no writes.
   - Pause/resume: version checks, stale 409, engine skips paused parties after resume pause takes effect.
   - Endpoint permission matrix: VIEW_REMINDERS (OWNER, STAFF ok; PORTAL_USER 403; non-member 404); pause/resume (OWNER, STAFF ok; PORTAL_USER 403; non-member 404); archived app → 409.
   - Id scoping: partyId from other app/workspace → 404.
   - Pagination: size > 50 → 400; page 0/1 work.
   - Immutability trigger: direct SQL UPDATE/DELETE/TRUNCATE on reminder_events fails.
   - Startup validation: each out-of-range property causes `IllegalStateException` with fixed text.
   - Logs: logger output contains no SECRET_MARKER_123, no email address, no exception stack trace.
   - One app failure does not stop other apps (exception caught, totalFailed++).
   - V10 applies on top of V9: migration succeeds, tables exist.
   - Slice 0 tests: blocked storage.open holds no DB connection/app row lock; second download completes.
2. Append D-101 to D-108 verbatim to README 14.1.
3. Update `docs/STATUS.md`: tick "1f. Reminder engine (PLAN-0014)"; add log line `| YYYY-MM-DD | PLAN-0014: reminder engine (Phase 1, step 1f) | <model> | Done |`; add Known gaps (retention of reminder_events and outbox rows; no portal links in reminders); set Next task to `PLAN-0015: forms and dashboard generation (Phase 1, step 1g)`.
4. Final check: `cd backend && mvn test` green; report total test count (baseline 267).

## Checks
- `cd backend && mvn test` green after every slice.
- All required tests in Slice 4 exist and pass.
- `SELECT * FROM reminder_events LIMIT 1` returns a row (or no rows) — no SQL error.
- `\d reminder_events` shows immutability trigger, unique index, CHECK constraints.
- Startup with REMINDER_INTERVAL_MINUTES=4 throws `IllegalStateException` with fixed text.

## Acceptance criteria
- [ ] All slice checks pass and suite is green.
- [ ] D-101 to D-108 appended verbatim to README 14.1.
- [ ] No git commands were run; no decision text was changed in this plan.
- [ ] docs/STATUS.md updated; Known gaps added.

## Risks and assumptions
- Assumption: `email_outbox` priority ordering between account and reminder rows can be enforced by setting reminder send_after = now + 1 second (account emails use send_after = now). If OutboxService already queries `ORDER BY send_after ASC`, this is sufficient. If not, executor must add an ordering column or use kind ordering in the query — do not improvise, set BLOCKED with the exact query.
- Assumption: `findOwnersWithVerifiedEmail` joins `memberships` and `users` via JdbcClient (not JPA) scoped by workspace_id, role = 'OWNER', email_verified_at IS NOT NULL, ordered by users.created_at ASC, LIMIT 5. This reuses the JdbcClient pattern from other stores; MembershipRepository (JPA) is NOT used for this query.
- Assumption: PartyStore's `pauseReminders`/`resumeReminders` use the same optimistic-lock conditional UPDATE pattern as other party mutations (WHERE version = :expectedVersion), returning rows updated for stale-version detection.

## Decisions needed
- None. D-101 to D-108 are binding and cover all open questions. One note: D-104 says "outbox backlog guard: when QUEUED rows in email_outbox exceed MAX_OUTBOX_BACKLOG the whole run does nothing." "Whole run" is interpreted as: the entire scheduler invocation for all apps is skipped (not per-app). This matches "logs the count." If the orchestrator intends per-app skip, the executor must set BLOCKED.

## STATUS.md update (executor does this)
- Tick: `- [x] 1f. Reminder engine (PLAN-0014)`
- Log line: `| YYYY-MM-DD | PLAN-0014: reminder engine (Phase 1, step 1f) | <model> | Done |`
- Known gaps add: `- Retention job for reminder_events and old outbox rows is not in v1 (D-108).` and `- Reminder emails contain no portal links since the portal is not built yet (D-105).`
- Next task: `PLAN-0015: forms and dashboard generation (Phase 1, step 1g)`

---
## Decisions block (append CHARACTER FOR CHARACTER to README 14.1 as D-101 to D-108)
D-101 Persistence (Flyway V10, JdbcClient, no JPA, RESTRICT foreign keys): reminder_events(id uuid PK, workspace_id, app_id, party_id, rule_id text, subject_id uuid null (the charge id, document request id, or party id), recipient_kind PARTY|OWNER, recipient_user_id uuid null (set for OWNER rows), scheduled_for date, status QUEUED|SKIPPED, skip_reason text null, outbox_id uuid null references email_outbox(id), message_key text, created_at timestamptz default clock_timestamp()). A unique index on (app_id, rule_id, subject_id, recipient_kind, coalesce(recipient_user_id, '00000000-0000-0000-0000-000000000000'), scheduled_for) makes every reminder idempotent. Composite foreign key to parties(id, workspace_id, app_id). CHECKs: skip_reason is not null iff status SKIPPED; outbox_id is not null iff status QUEUED. A trigger blocks UPDATE, DELETE and TRUNCATE (tests clean through TestDbHelper). parties gets reminders_paused_at timestamptz null; email_outbox gets reply_to text null and SmtpEmailSender uses it as Reply-To when present, otherwise MAIL_REPLY_TO. reminder_events stores no email addresses, bodies, or amounts.
D-102 What each rule trigger means (evaluated from the app's CURRENT definition, enabled and non-archived rules only, "today" in the app time zone): charge.due (recipient party): every CHARGE of the rule's partyType (kinds recurring, manual and late_fee; not reversed; outstanding greater than 0) with scheduled_for = due_date + offsetDays; charge.overdue (recipient owner): the same charges with scheduled_for = due_date + offsetDays (0 to 90); document.due (party or owner as the rule says): document requests that are required, status PENDING or REJECTED, not cancelled, due_date not null, scheduled_for = due_date + offsetDays; document.expiry (party): requests with status APPROVED and expires_on not null, scheduled_for = expires_on + offsetDays; lease.end (owner): non-archived parties of the rule's partyType whose rule.dateField holds a valid ISO date, scheduled_for = that date + offsetDays. PARTY recipient address: the first non-archived field of type email in the party type's definition order; missing or invalid gives SKIPPED NO_EMAIL. OWNER recipients: every workspace member with role OWNER and a verified email (email_verified_at not null), at most 5, one reminder per owner.
D-103 Timing and catch-up: a reminder is due when scheduled_for is within [today - REMINDER_CATCHUP_DAYS, today] (default 2, range 0 to 7) AND the local time in the app zone is at or after REMINDER_SEND_HOUR (default 9, range 0 to 23). Older reminders are never sent (no spam after an outage; they are simply not created). State is rechecked inside the app-locked transaction at send time: a charge paid or reversed, a request received, approved, rejected or cancelled since, or an archived party, means no reminder (no event row is needed for these; nothing is queued).
D-104 Safety gates in this order, each ending in a SKIPPED event with a fixed skip_reason where a subject exists: global switch REMINDERS_ENABLED (default false; off means the job does nothing); app active and not archived; the workspace has at least one OWNER with a verified email (otherwise UNVERIFIED_OWNER, per D-50); recipient address present (NO_EMAIL); party not archived and reminders_paused_at null (PAUSED); per-party daily cap MAX_REMINDERS_PER_PARTY_PER_DAY (default 2, counted per app-local day over QUEUED events; over the cap gives RATE_LIMIT); per-run cap MAX_REMINDERS_PER_APP_RUN (default 100); outbox backlog guard: when QUEUED rows in email_outbox exceed MAX_OUTBOX_BACKLOG (default 200) the whole run does nothing and logs the count. The engine inserts email_outbox rows (kind REMINDER, lower priority than account emails) and reminder_events in the same transaction; it never calls an EmailSender.
D-105 Message content: fixed plain-text templates in code, one per messageKey from the definition enums, with variables party name, workspace name, app name, label, amount in USD, due date, days overdue or until due, document item label, expiry date. Every variable is sanitized (control characters removed, angle brackets removed, truncated to 120 characters) and amounts are formatted from integer cents. Subjects follow fixed patterns. The footer names the workspace and FollowWise as the sender and tells the recipient they can ask the sender to pause the reminders. Reply-To is the workspace's earliest verified OWNER email (stored in email_outbox.reply_to); the From address stays the system sender. No links, since no portal exists yet. Templates never include other parties' data.
D-106 Endpoints under /api/workspaces/{wid}/apps/{aid}: GET .../reminders/preview (what the engine would queue now, dry run, no writes, at most 200 items with party display name, rule id, message key, scheduled_for, or a skip reason; never email addresses); POST .../parties/{partyId}/reminders/pause and .../resume (EDIT_DATA, optimistic version bump per D-77 rules, 409 app_archived on archived apps); GET .../reminder-events?partyId=&status=&page=&size= (history, default size 20, maximum 50, no bodies and no addresses). New permissions VIEW_REMINDERS (OWNER, STAFF) for the two GETs; pause and resume use EDIT_DATA. PORTAL_USER 403, non-members 404 (D-42). There is no "send now" endpoint in v1. Every query scopes by workspace_id AND app_id.
D-107 Scheduling: a Spring job every REMINDER_INTERVAL_MINUTES (default 15, range 5 to 120) with fixed delay, a random initial delay, and conditional on app.scheduler-enabled AND REMINDERS_ENABLED. Each app is its own transaction taking the app row lock with FOR UPDATE SKIP LOCKED; a failure in one app does not stop the others; created_by is not needed (no user rows are written); logs contain only counts per run (apps processed, queued, skipped by reason, failed) and opaque app ids, never names, addresses, amounts, labels, or exception messages. Startup validates every numeric setting with fixed-text errors. The same engine class serves the scheduler and the preview endpoint (dry run).
D-108 Privacy and retention: reminder_events hold ids and reasons only; recipient addresses live only in email_outbox.to_address (bodies are redacted after sending by D-47). A retention job for old events and outbox rows is not in this plan (known gap). Test fixtures use only invalid or example addresses.

---

## Orchestrator amendments (binding, override anything above that conflicts)
A. Run no git command. The Decisions block stays character for character; also append D-109 (section F) after D-108.
B. Slice 0: do not call a private @Transactional method from inside DocumentService (Spring proxies ignore self-invocation). Do the lookup with a read-only TransactionTemplate (or a separate bean) and call documentStorage.open outside any transaction. The two tests stay.
C. Backlog guard: count ALL rows with status QUEUED in email_outbox (the table has no workspace column; delete countQueuedOutbox(workspaceId)). Check it once at the start of each scheduler run and at the start of each real processApp; when it blocks, log the count and write nothing. Preview ignores the guard and reports blockedByBacklog (boolean).
D. Outbox priority: insert reminder rows with kind REMINDER and send_after = now. Do NOT delay them by one second. Do not change the worker query unless a test proves account kinds are not claimed first. Add a test: a REMINDER row created earlier is claimed after a VERIFY_EMAIL row created later.
E. Gate behavior: UNVERIFIED_OWNER applies to every candidate (party and owner recipients) and writes SKIPPED events with that reason (idempotent through the unique index); the other gates behave per D-104. A SKIPPED event is final for its candidate and date (it is never retried, even after the cause is fixed); document this in the report. No event row is written when REMINDERS_ENABLED is false, the app is missing or archived, the backlog guard blocks, or a state recheck fails. The per-party daily cap counts QUEUED PARTY-recipient events of that party using (created_at AT TIME ZONE <app time zone>)::date equal to the app-local today. The per-run cap stops the WHOLE app run (log the count once).
F. Append to README 14.1 after D-108:
D-109 Owner-directed reminders are capped per recipient: MAX_REMINDERS_PER_OWNER_PER_DAY (default 10, range 1 to 50, counted per app-local day over QUEUED events for that recipient_user_id in the app being processed); over the cap gives a SKIPPED RATE_LIMIT event. The per-party daily cap counts only PARTY-recipient events. SKIPPED events are final for their candidate (the unique index prevents a retry even after the cause is fixed). The outbox backlog guard counts all QUEUED rows of email_outbox. Preview is read-only, takes no lock, and works when REMINDERS_ENABLED is false. Digest emails and retention of reminder_events are future work (retention is blocked by the immutability trigger and the foreign key to email_outbox).
 Also: add MAX_REMINDERS_PER_OWNER_PER_DAY=10 to .env.example and to the startup validation.
G. Dry run (preview): a read-only transaction, no app lock, no writes; same logic as a real run; items ordered by scheduled_for, party id, subject id, recipient; at most 200 items including skipped ones; response also carries remindersEnabled and blockedByBacklog. Real runs lock the app row as the plan says.
H. Candidate queries: add set-based read-only queries (in LedgerStore, DocumentRequestStore and PartyStore) that filter by the date window IN SQL (for example due_date between today - catchup - offset and today - offset), scoped by workspace_id and app_id, ordered, LIMIT run cap + 1, with outstanding computed as COALESCE(SUM(amount_cents),0)::bigint. Never load all rows and filter in Java. List the new methods in the report.
I. Reply-To: SmtpEmailSender uses the per-message reply_to only if it matches the existing email syntax check and contains no CR or LF; otherwise it falls back to MAIL_REPLY_TO. reply_to is set only on PARTY-recipient emails; OWNER-recipient emails have reply_to null. Tests: a reply_to containing CRLF is ignored; OWNER rows have no reply_to.
J. Scheduler: conditional on BOTH app.scheduler-enabled AND app.reminders-enabled (from REMINDERS_ENABLED); ReminderProperties fails startup if REMINDERS_ENABLED is not exactly "true" or "false" (blank fails); keep the defensive flag check in processApp. Tests: scheduler bean absent when either switch is false, present when both are true.
K. Templates (exact text; variables {party} {workspace} {app} {label} {amount} {due} {days} {item} {expiry}; {days} is the whole number of days between the app-local today and the target date; {amount} is formatted like $1,234.56 from integer cents). Party-facing footer F1: "Sent on behalf of {workspace} by FollowWise. Reply to this email to reach {workspace}. To stop these reminders, ask {workspace} to pause them." Owner-facing footer F2: "Sent by FollowWise for {workspace}. You can pause reminders for a person in the app."
 charge_reminder_before | {label} of {amount} is due on {due} | Hello {party}, this is a friendly reminder that {label} of {amount} is due on {due}, in {days} days. If you have already paid, thank you and please ignore this message. + F1
 charge_reminder_due | {label} of {amount} is due today | Hello {party}, {label} of {amount} is due today ({due}). If you have already paid, thank you and please ignore this message. + F1
 charge_reminder_late | {label} of {amount} is {days} days overdue | Hello {party}, our records show that {label} of {amount} was due on {due} and is now {days} days overdue. Please arrange payment when you can. If you have already paid, please let {workspace} know by replying to this email. + F1
 charge_overdue_owner | Overdue: {party} owes {amount} | In {app}: {party} has an overdue charge, {label} of {amount}, due on {due} ({days} days overdue). + F2
 doc_reminder_before | Please send {item} by {due} | Hello {party}, {workspace} is waiting for {item}. It is due on {due}, in {days} days. Please send it when you can by replying to this email. + F1
 doc_reminder_due | Please send {item} today | Hello {party}, {workspace} is waiting for {item}, due today ({due}). Please send it by replying to this email. + F1
 doc_reminder_late | {item} is {days} days overdue | Hello {party}, {workspace} is still waiting for {item}, which was due on {due} ({days} days ago). Please send it by replying to this email. + F1
 doc_expiry_reminder | {item} expires on {expiry} | Hello {party}, the {item} on file with {workspace} expires on {expiry}, in {days} days. Please send an updated copy before then. + F1
 doc_overdue_owner | Overdue: {item} from {party} | In {app}: {party} has not provided {item}. It was due on {due} ({days} days overdue). + F2
 lease_ending_owner | Lease ending: {party} on {expiry} | In {app}: the lease for {party} ends on {expiry}, in {days} days. + F2
 Tests assert the exact subject for every key with sample values, bodies contain no raw angle brackets or control characters, and no other party's data.
L. Pause and resume: expectedVersion is checked first (409 stale_version); archived app 409 app_archived; archived party 409 party_archived; pausing an already paused party (or resuming an active one) is a 200 no-op without a version bump. PartyResponse gains remindersPaused (boolean).
M. Tests cleanup: every existing test class that deletes email_outbox rows must first delete reminder_events (route through TestDbHelper.cleanDatabase; fix any class that deletes outbox directly). Add to STATUS Known gaps: "Retention of reminder_events and old email_outbox rows needs a controlled purge design because reminder_events is immutable and references email_outbox."
N. Extra tests: SKIPPED UNVERIFIED_OWNER events are written and stay final after the owner verifies; per-owner cap gives RATE_LIMIT; per-run cap stops the whole app run; daily cap day boundary in two time zones using created_at close to local midnight; preview with REMINDERS_ENABLED=false; global backlog guard; a party whose email field holds an invalid value; two apps processed concurrently; the engine never calls an EmailSender (grep and a mock with verifyNoInteractions); the exact-subject tests from K.
O. In the report list: new store methods, the lock sequence of processApp and the pause/resume methods, the grep for @Transactional in the reminder package, accurate test counts (baseline 267), and every network access made (none expected).

---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks:
