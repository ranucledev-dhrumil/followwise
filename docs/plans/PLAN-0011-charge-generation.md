# PLAN-0011: Charge generation

Status: APPROVED        <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 1, step 1d-ii
Author: Claude Sonnet 4.6   Executor: (model that executed)
Branch: plan-0011 (created by the founder; AI models run no git)

## Goal
A scheduled, idempotent job (plus an owner-triggered endpoint) that creates recurring charges and late fees defined by each app's chargeSchedules, using the PLAN-0010 ledger. D-87 to D-92 govern all behaviour.

## Out of scope
- Reminders and notifications; lease start/end handling; proration; refunds
- Frontend, portal, multi-currency
- Any ledger changes beyond V8; audit log table

## Inputs
- AGENTS.md, docs/STATUS.md, README 6, 7.3, 8.5, 14.2, D-41, D-42, D-62–D-92
- `backend/src/main/resources/db/migration/V7__ledger.sql`
- `backend/src/main/java/com/followwise/ledger/` (all files)
- `backend/src/main/java/com/followwise/app/AppStore.java`, `AppConfig.java`
- `backend/src/main/java/com/followwise/party/PartyStore.java`
- `backend/src/main/java/com/followwise/outbox/` (scheduler pattern)
- `backend/src/main/resources/appdefinition/app-definition-v1.schema.json` (chargeSchedule)
- `backend/src/main/resources/appdefinition/rental-property-manager.json` (template)
- `.env.example`, `backend/src/main/resources/application.properties`

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| `backend/src/main/resources/db/migration/V8__charge_generation.sql` | create | SYSTEM user |
| `backend/src/main/resources/application.properties` | edit | add scheduler/window props |
| `.env.example` | edit | add 4 new env vars |
| `backend/src/main/java/com/followwise/ledger/ChargeGenerationResult.java` | create | result record |
| `backend/src/main/java/com/followwise/ledger/ChargeGeneratorService.java` | create | core logic |
| `backend/src/main/java/com/followwise/ledger/ChargeGenerationScheduler.java` | create | Spring @Scheduled |
| `backend/src/main/java/com/followwise/ledger/LedgerController.java` | edit | add generate endpoint |
| `README.md` | edit | append D-87–D-92 to section 14.1 |
| `docs/STATUS.md` | edit | tick 1d-ii, log line, Next task |
| `backend/src/test/java/com/followwise/ledger/ChargeGenerationTest.java` | create | all required tests |

## Steps

### Slice 0 — V8 migration: SYSTEM user
1. Create `V8__charge_generation.sql`: `INSERT INTO users(id, email, password_hash, email_verified, created_at) VALUES ('00000000-0000-0000-0000-000000000001', 'system@followwise.invalid', '$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', true, now()) ON CONFLICT DO NOTHING;` (the hash is a structurally valid bcrypt prefix followed by chars that form no real hash — BCryptPasswordEncoder.matches() returns false for any password against it).
2. No membership row for the system user.
3. Check: `cd backend && mvn test` green; migration applies on top of V7.

### Slice 1 — Config + result record + ChargeGeneratorService
1. `application.properties`: add `app.scheduler.enabled=${SCHEDULER_ENABLED:false}`, `app.charge.catchup-days=${CHARGE_CATCHUP_DAYS:35}`, `app.charge.lead-days=${CHARGE_LEAD_DAYS:14}`, `app.charge.generation-interval-minutes=${CHARGE_GENERATION_INTERVAL_MINUTES:60}`, `app.charge.max-generated-per-app-run=${MAX_GENERATED_PER_APP_RUN:500}`.
2. `.env.example`: add `SCHEDULER_ENABLED=`, `CHARGE_CATCHUP_DAYS=35`, `CHARGE_LEAD_DAYS=14`, `CHARGE_GENERATION_INTERVAL_MINUTES=60`, `MAX_GENERATED_PER_APP_RUN=500`.
3. Create `ChargeGenerationResult.java`: record with fields `boolean dryRun`, `int created`, `int skippedExisting`, `int skippedInvalid`, `int skippedLocked`, `int failed`, `LocalDate windowStart`, `LocalDate windowEnd`, `LocalDate today`.
4. Create `ChargeGeneratorService.java` annotated `@Service`. Inject `LedgerStore`, `AppStore`, `PartyStore`, `Clock`, and config values via `@Value`. Implement:
   - `generateForApp(UUID wid, UUID aid, UUID callerUserId, boolean dryRun)` → `ChargeGenerationResult` (D-91 path: plain FOR UPDATE if called from endpoint; SKIP LOCKED from scheduler — pass a flag `boolean skipLocked`).
   - App lock via `SELECT id FROM apps WHERE id=:aid AND workspace_id=:wid AND archived_at IS NULL FOR UPDATE [SKIP LOCKED]`; if no row returned and skipLocked throw app-not-found or return skippedLocked=1.
   - Load current app definition; compute today using Clock + app time zone; compute windowStart = today − catchupDays, windowEnd = today + leadDays.
   - Iterate enabled, non-archived chargeSchedules. For recurring: iterate non-archived parties of matching partyType; for each due month in window compute due date, check party creation date, resolve amount and dueDay; call `LedgerStore.insertEntry()` with ON CONFLICT DO NOTHING, increment created or skippedExisting. For late-fee: query outstanding recurring/manual charges by partyType, filter overdue by graceDays; insert one late-fee charge per qualifying charge using period_key = overdue charge id; ON CONFLICT DO NOTHING.
   - Stop an app when count ≥ MAX_GENERATED_PER_APP_RUN; if MAX_LEDGER_ENTRIES_PER_APP reached, log count only.
   - If dryRun, do not call insertEntry; track counts only.
   - System user UUID constant `SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")`.
   - Log at INFO: counts only; no names, emails, amounts, or labels.
5. Check: `cd backend && mvn test` green (no new tests yet; compilation sufficient).

### Slice 2 — Scheduler
1. Create `ChargeGenerationScheduler.java`: `@Component`, `@ConditionalOnProperty(name="app.scheduler.enabled", havingValue="true")`. Constructor-injects `AppStore`, `ChargeGeneratorService`, and interval config.
2. `@Scheduled(fixedDelayString="${app.charge.generation-interval-minutes}000", initialDelayString="#{T(java.util.concurrent.ThreadLocalRandom).current().nextInt(30000)}")` on a `runAll()` method. `runAll()` calls `AppStore.findAllActiveWorkspaceAppIds()` (or equivalent), calls `generateForApp(wid, aid, SYSTEM_USER_ID, false, skipLocked=true)` per app in a try-catch, logs counts; failures increment failed counter.
3. Check: `cd backend && mvn test` green; write one test that the scheduler bean is absent when `app.scheduler.enabled=false` (using `@SpringBootTest` with the property set) and present when `true`.

### Slice 3 — Controller endpoint
1. In `LedgerController.java` add: `POST /api/workspaces/{wid}/apps/{aid}/charges/generate?dryRun=false`. Requires permission `MANAGE_LEDGER` (OWNER only per D-42). Calls `ChargeGeneratorService.generateForApp(wid, aid, currentUserId, dryRun, skipLocked=false)`. Returns 200 with `ChargeGenerationResult` JSON. Archived app: propagate 409 `app_archived`. Id scoping mismatch: 404.
2. Check: `cd backend && mvn test` green.

### Slice 4 — Integration tests + docs
1. Create `ChargeGenerationTest.java` covering (use mutable test Clock, Testcontainers):
   - RPM tenant dueDay from field, rent from money field → correct due date and amount.
   - Fixed amount and fixed dueDay.
   - Window boundaries (exactly today−35 and today+14 in; one day outside each out).
   - Party created after this month's due date → first charge next month.
   - Party created on the due date → charge created for that month.
   - Re-run → skippedExisting; two concurrent runs → no duplicates.
   - Clock jumps 3 months → only in-window months created; months outside window skipped.
   - Month-end and leap-day dates handled correctly.
   - Invalid/missing field value → skippedInvalid.
   - Archived party, disabled schedule, archived schedule, archived app → skipped.
   - partyType filter: schedule only targets matching type.
   - period_key format YYYY-MM; label format and 120-char truncation.
   - App time zones on opposite sides of date line → different "today".
   - Late fee: due + graceDays is not yet late; one day later is late.
   - One fee per charge across repeated runs.
   - No fee for fully paid, partly-paid-then-paid-in-full, or reversed charges.
   - Partly paid charge still gets a fee.
   - No fee for late_fee charges; reversed fee not regenerated; manual charges can earn fees.
   - Disabled late-fee schedule → nothing created.
   - System user cannot log in (POST /api/auth/login → 401) and has no membership.
   - `created_by` of generated rows = SYSTEM_USER_ID.
   - Per-run cap stops at 500.
   - Dry run writes nothing and returns correct counts.
   - Endpoint permission matrix: OWNER 200, STAFF 403, PORTAL_USER 403, non-member 404.
   - Archived app → 409; id scoping → 404.
   - Scheduler bean absent when SCHEDULER_ENABLED=false, present when true.
   - Logs contain no value matching `SECRET_MARKER_123` from party names or labels; no emails.
   - V8 applies on top of V7.
2. Append D-87–D-92 to README section 14.1 (table rows, verbatim wording from this plan's Decisions block).
3. Update `docs/STATUS.md`: tick `1d-ii`, add log line, set Next task to `PLAN-0012: documents and storage (Phase 1, step 1e)`.
4. Check: `cd backend && mvn test` — all passing; record exact counts (existing + new) in Execution report.

## Checks (how to prove it works)
- After each slice: `cd backend && mvn test` exits 0.
- Final: report "Tests run: X, Failures: 0, Errors: 0, Skipped: Y".

## Acceptance criteria
- [ ] V8 migration applies; system user exists; login attempt returns 401.
- [ ] Recurring charges generated correctly for RPM template parties; idempotent on re-run.
- [ ] Late fees created after grace period; not duplicated; not regenerated after reversal.
- [ ] Window bounds and party creation date filter confirmed by tests.
- [ ] Dry run returns counts but writes nothing.
- [ ] Endpoint enforces MANAGE_LEDGER; archived app 409; scope errors 404.
- [ ] Scheduler bean conditional on SCHEDULER_ENABLED.
- [ ] No personal data in logs (SECRET_MARKER_123 test).
- [ ] All tests green.

## Risks and assumptions
- `AppStore` exposes a method to list all active (non-archived) app+workspace id pairs; if not, the executor adds one (not an improvisation — it is required by D-87).
- `LedgerStore.insertEntry()` already supports ON CONFLICT DO NOTHING via caller-controlled flag or returns a boolean; if not, the executor adds an overload.
- `@Scheduled(initialDelayString="#{SpEL}")` is supported in Spring Boot 4.1 — confirmed by Spring docs (fixedDelay + initialDelay both accept SpEL in string form via `@Scheduled`).
- Bcrypt string `$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` (53 A's) is not a real hash of any password; BCryptPasswordEncoder.matches() returns false. If the AuthService throws instead of returning false, the test catches the exception and treats it as "login failed".

## Decisions needed
(none — D-87 to D-92 are approved)

## STATUS.md update (executor does this)
- Tick: `- [x] 1d-ii. Charge generation (PLAN-0011)`
- Log line: `| YYYY-MM-DD | PLAN-0011: charge generation (Phase 1, step 1d-ii) | <model> | Done |`
- Next task: `PLAN-0012: documents and storage (Phase 1, step 1e)`

---
## Decisions
<!-- Append verbatim as D-87 to D-92 in README 14.1 -->
D-87 Generation reads the app's CURRENT definition (app time zone for "today") and processes each active (unarchived) app in its own transaction that first takes the app row lock (SELECT id FROM apps WHERE id=:aid AND workspace_id=:wid AND archived_at IS NULL FOR UPDATE SKIP LOCKED; an app locked by another runner is skipped this round). A failure in one app is logged without personal data and does not stop the other apps. Entries are created through the same insert rules and constraints as PLAN-0010 (composite keys, CHECKs, immutability). Generated entries are created_by a dedicated SYSTEM user (Flyway V8 inserts a user with a fixed UUID, email system@followwise.invalid, a password hash that no password can match, and it never appears in memberships). Login for that user is impossible, which a test proves.

D-88 Recurring schedules (kind recurring, enabled, not archived): for every non-archived party of the schedule's partyType, the due date for a month is the dueDay (fixed, or read from the party's integer field, 1 to 28) in that month. A charge is generated when its due date lies in the window [today - CATCHUP_DAYS, today + LEAD_DAYS] (defaults 35 and 14, env configurable) AND the due date is on or after the party's creation date in the app time zone (a new party never receives charges dated before it existed). Amount comes from the schedule: fixed amountCents, or the party's money field (integer cents, greater than 0). If the field value is missing or invalid the party is skipped for that schedule and counted as skippedInvalid. Charge fields: kind recurring, schedule_id, period_key YYYY-MM of the due date, label "<schedule label> <YYYY-MM>" cut to 120 characters, due_date, currency USD. Idempotency is the existing unique key (app_id, party_id, schedule_id, period_key): inserts use ON CONFLICT DO NOTHING and count skippedExisting. Archived parties are skipped. Lease start and end fields are not used in v1 (known gap: owners archive tenants whose lease ended).

D-89 Late fees (kind late_fee, enabled, not archived): for each CHARGE of kind recurring or manual, of a party of the schedule's partyType, not reversed, with outstanding greater than 0 and due_date + graceDays < today (app time zone), exactly one late-fee CHARGE is created: kind late_fee, schedule_id of the late-fee schedule, period_key = the overdue charge id, amount fixed or from the party's money field, due_date = today, label "Late fee" plus the original label cut to 120 characters, party archived or not (fees accrue until archived parties are skipped). Late-fee charges never generate late fees. Idempotency uses the same unique key with ON CONFLICT DO NOTHING; a late fee that the owner reversed is never regenerated. Charges that were paid in full before the grace period ended never get a fee.

D-90 Scheduling: a Spring scheduled job runs every CHARGE_GENERATION_INTERVAL_MINUTES (default 60, minimum 5) only when SCHEDULER_ENABLED is true, with a short random initial delay, and uses fixed delay so runs never overlap in one instance. Each run is capped by MAX_GENERATED_PER_APP_RUN (default 500) charges per app, stops for an app when MAX_LEDGER_ENTRIES_PER_APP is reached (logged once), and logs only counts per run: apps processed, created, skippedExisting, skippedInvalid, skippedLocked, failed. No names, emails, or amounts in logs.

D-91 Owner endpoint: POST /api/workspaces/{wid}/apps/{aid}/charges/generate?dryRun=true|false (default false) runs the same logic for that one app and returns {dryRun, created, skippedExisting, skippedInvalid, windowStart, windowEnd, today}. Permission MANAGE_LEDGER (OWNER only); STAFF 403, PORTAL_USER 403, non-member 404 (D-42); archived app 409 app_archived; at most one run per app per 10 seconds is not required but the app lock must serialize runs. A dry run writes nothing and takes no lock beyond a read.

D-92 Time and money rules from D-84 apply: the Clock bean provides "today", evaluated in each app's own time zone; created_at comes from the database. The generator never edits or deletes existing entries. A generated charge may be reversed like any other charge under D-81.
---

## Orchestrator amendments (binding, override anything above that conflicts)
A. Run no git command. Use the corrected D-89 and D-91 wording and the new D-93 from section K, not the plan's own Decisions block where they differ.
B. SYSTEM user (V8): read the real users columns in V2 and V4 and insert only existing columns. Do NOT use email_verified (it does not exist; leave email_verified_at NULL). password_hash is '{bcrypt}$2a$10$' followed by 53 characters from [./A-Za-z0-9] (unmatchable, with the DelegatingPasswordEncoder prefix); locked_until = 'infinity'; email system@followwise.invalid; fixed id; fill any NOT NULL columns with their defaults. Add an identity SystemUser class (ID, EMAIL). AuthService.login treats the system user like an unknown email (dummy hash, the same generic 401, never reaching lockout or password logic). forgot-password issues no token and no outbox row for it (still 204); resend-verification, verify-email and reset-password refuse it. Tests: login as the system email gives the generic 401; forgot-password creates no auth_token or outbox row; no membership row; direct SQL shows locked_until = infinity.
C. Properties: reuse the existing app.scheduler-enabled (SCHEDULER_ENABLED). Do NOT add app.scheduler.enabled and do NOT edit SCHEDULER_ENABLED in .env.example. New properties: app.charge.catchup-days, app.charge.lead-days, app.charge.generation-interval-minutes, app.charge.max-generated-per-app-run with env CHARGE_CATCHUP_DAYS=35, CHARGE_LEAD_DAYS=14, CHARGE_GENERATION_INTERVAL_MINUTES=60, MAX_GENERATED_PER_APP_RUN=500 (names and defaults only in .env.example). Startup validation with fixed-text IllegalStateException: interval 5..1440, catch-up 0..90, lead 0..60, cap 1..2000. Test each failure.
D. Scheduler: fixedDelayString = "#{T(java.lang.Math).max(5, ${app.charge.generation-interval-minutes}) * 60000}", initial delay random 0 to 30 seconds. Add AppStore.findActiveAppIds() returning (workspaceId, appId) for unarchived apps ordered by app id. The loop calls the generator through the Spring bean so each app is its own transaction; an exception rolls back only that app, increments failed, and the loop continues. Tests keep the scheduler off by default and call the service directly.
E. Service rules: generated entries are always created_by the SYSTEM user, also for owner-triggered runs (callerUserId is only for the permission check). Real runs are @Transactional and lock the app row with FOR UPDATE SKIP LOCKED (scheduler; locked app counts as skippedLocked) or FOR UPDATE (endpoint, waits). Dry run is read-only: no lock, no insert; created means the number that WOULD be created and skippedExisting comes from existence queries; a test asserts a dry run and the following real run report the same numbers and the dry run wrote nothing. Insert uses ON CONFLICT (app_id, party_id, schedule_id, period_key) WHERE entry_type = 'CHARGE' AND period_key IS NOT NULL DO NOTHING (the index predicate must be repeated); zero rows affected counts as skippedExisting. Load the non-archived parties of a type in one query ordered by id; process months ascending. Parse due day and amount from the party data in Java: due day an integral number 1 to 28; amount an integral number 1 to 100,000,000,000; anything else is skippedInvalid (never an exception). Compute outstanding with COALESCE(SUM(amount_cents),0)::bigint. A reversed generated charge is never regenerated (the unique key remains); add a test and note it in the report.
F. Late fees: archived parties are skipped (archiving stops accrual). The eligibility query runs inside the same app-locked transaction as the inserts.
G. Endpoint: dryRun accepts only "true" or "false" (anything else is 400 with fixed text); no request body is expected; add this path to the size filter with a 1024-byte limit; the response contains dryRun, created, skippedExisting, skippedInvalid, skippedLocked, failed, windowStart, windowEnd, today. Check order is the same as PLAN-0010 amendment E.
H. Extra tests: reversed recurring charge not regenerated; no late fee for archived parties; scheduler path returns skippedLocked while another transaction holds the app lock; the endpoint waits and serializes with a concurrent run (no duplicates); startup validation failures from C; system-user guards from B; amount 0 or above 100,000,000,000, and due day 0, 29, a string, or a decimal, are skippedInvalid; created_by of owner-triggered rows is the SYSTEM user; one app throwing does not stop the others; captured logs contain neither SECRET_MARKER_123 (placed in a party name and a schedule label) nor any email address.
I. Template path fix: templates are under backend/src/main/resources/appdefinition/templates/.
J. The planner skipped the VERIFY section. In the report, give the verified answer and a source for each of the four VERIFY items from the original brief, or state which ones the tests prove.
K. Decision wording. In D-89 replace "party archived or not (fees accrue until archived parties are skipped)" with "archived parties are skipped, so archiving a party stops fee accrual". In D-91 the response also includes skippedLocked and failed. Append after D-92:
D-93 The SYSTEM user (fixed UUID, locked_until infinity, unmatchable {bcrypt} hash) is protected in code: login treats it as an unknown email, and forgot-password and verification never issue tokens or emails for it. All generated entries are created_by this user, including owner-triggered runs. The charge generator reuses app.scheduler-enabled and validates its numeric settings at startup (interval 5 to 1440 minutes, catch-up 0 to 90 days, lead 0 to 60 days, per-run cap 1 to 2000). A reversed generated charge is never regenerated.
L. Report: list every deviation. Log line format: | YYYY-MM-DD | PLAN-0011: charge generation (Phase 1, step 1d-ii) | <model> | Done |

---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks: