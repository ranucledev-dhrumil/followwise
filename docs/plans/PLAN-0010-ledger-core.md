# PLAN-0010: Ledger core

Status: APPROVED
Phase / step: Phase 1, step 1d-i
Author: Claude Sonnet 4.6   Executor: (model that executed)
Branch: plan-0010 (created by the founder; AI models run no git)

## Goal
Append-only ledger of charges, payments, and reversals per app with manual charge creation, payment recording, reversals, derived balances, and charge listing. REST API only; no automatic charge generation, no reminders, no frontend, no portal.

## Out of scope
Charge generation and late fees (PLAN-0011), reminders, frontend, portal, refunds as separate concept, multi-currency, payment gateways, import, audit log table.

## Inputs
- README sections 6, 7.3, 8.4, 14.2 and decision rows D-41, D-42, D-62–D-79
- AGENTS.md sections 4 and 5; docs/STATUS.md
- backend/src/main/java/com/followwise/party/PartyStore.java, RecordStore.java, PartyService.java, PartyController.java, PartyPayloadParser.java, PartyExceptionHandler.java
- backend/src/main/java/com/followwise/app/AppStore.java, BodySizeFilter.java
- backend/src/main/java/com/followwise/workspace/Permission.java, AccessService.java
- chargeSchedule section of app-definition-v1.schema.json

## VERIFY results
1. **Postgres immutability trigger**: Use a `BEFORE UPDATE OR DELETE FOR EACH ROW` trigger plus a separate `BEFORE TRUNCATE FOR EACH STATEMENT` trigger (TRUNCATE bypasses row-level triggers). Both call `RAISE EXCEPTION`. Source: [neon.com](https://neon.com) and [postgresql.org](https://www.postgresql.org/docs/current/sql-createtrigger.html).
2. **Jackson 3 integral check**: `node.isIntegralNumber()` returns `false` for `100.0` and `1e3` (they are stored as `DoubleNode`), so `!node.isFloatingPointNumber() && node.isIntegralNumber() && node.canConvertToLong()` correctly rejects all float representations. Source: [javadoc.io](https://javadoc.io/doc/tools.jackson.core/jackson-databind).
3. **Clock bean override**: Declare `@Bean @ConditionalOnMissingBean Clock clock()` returning `Clock.systemUTC()` in a main `@Configuration`. In tests, a static nested `@TestConfiguration` with `@Bean @Primary Clock clock()` returning `Clock.fixed(...)` overrides it automatically. Source: [howtodoinjava.com](https://howtodoinjava.com).

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| db/migration/V7__ledger.sql | create | Table, indexes, CHECKs, immutability trigger |
| workspace/Permission.java | edit | Add VIEW_LEDGER, RECORD_PAYMENT, MANAGE_LEDGER |
| app/AppConfig.java | create | Clock bean (@ConditionalOnMissingBean) |
| ledger/LedgerEntry.java | create | Record (domain model) |
| ledger/LedgerStore.java | create | JdbcClient persistence |
| ledger/LedgerService.java | create | Business rules, concurrency, idempotency |
| ledger/LedgerController.java | create | REST endpoints |
| ledger/LedgerPayloadParser.java | create | Strict JSON parsing for each write shape |
| ledger/LedgerExceptionHandler.java | create | Maps ledger exceptions to ProblemDetail |
| ledger/Ledger*Exception.java (×4) | create | LedgerNotFoundException, LedgerConflictException, LedgerValidationException, LedgerBadRequestException |
| ledger/ChargeResponse.java | create | Charge + entries + outstandingCents + status |
| ledger/EntryResponse.java | create | Single ledger entry |
| ledger/LedgerSummaryResponse.java | create | totalChargedCents, totalPaidCents, balanceCents |
| ledger/PageResponse.java | create | Reuse or copy from party package |
| app/BodySizeFilter.java | edit | Add 8 KB per-path limit for ledger writes |
| application.properties | edit | app.max-ledger-entries-per-app |
| .env.example | edit | MAX_LEDGER_ENTRIES_PER_APP=20000 |
| party/PartyStore.java | edit | 2 String.toLowerCase() → toLowerCase(Locale.ROOT) |
| party/RecordStore.java | edit | 2 String.toLowerCase() → toLowerCase(Locale.ROOT) |
| README.md | edit | Append D-80–D-85 to section 14.1 |
| docs/STATUS.md | edit | Tick 1d-i, task log line, next task |

## Steps

### Slice 0 — Housekeeping (Locale.ROOT)
1. In `PartyStore.java` and `RecordStore.java`, replace the two calls to `escapeLike(q.trim()).toLowerCase()` with `escapeLike(q.trim()).toLowerCase(Locale.ROOT)`. Add `import java.util.Locale` if missing (four edits total; two files, one call each).
2. Add one test method `prefixSearch_localeRoot_stillWorks` in `PartyApiTest` that creates a party whose display name contains an uppercase letter and asserts it appears in a prefix search.
3. Check: `cd backend && mvn test` — all existing tests pass.

### Slice 1 — Migration and permissions
4. Create `V7__ledger.sql`: table `ledger_entries` with all columns from D-80, two UNIQUE partial indexes (`(app_id, idempotency_key) WHERE idempotency_key IS NOT NULL` and `(app_id, party_id, schedule_id, period_key) WHERE entry_type = 'CHARGE' AND period_key IS NOT NULL`), regular index on `(app_id, party_id, created_at, id)`, CHECK constraints from D-80. Create trigger function `prevent_ledger_modification()` with `RAISE EXCEPTION 'ledger_entries is immutable'`. Attach two triggers: `BEFORE UPDATE OR DELETE FOR EACH ROW` and `BEFORE TRUNCATE FOR EACH STATEMENT`.
5. Add `VIEW_LEDGER(OWNER, STAFF)`, `RECORD_PAYMENT(OWNER, STAFF)`, `MANAGE_LEDGER(OWNER)` to `Permission.java`.
6. Create `app/AppConfig.java` with `@Bean @ConditionalOnMissingBean Clock clock() { return Clock.systemUTC(); }`.
7. Check: `cd backend && mvn test` — all tests pass; Flyway output shows V7 applied.

### Slice 2 — Store
8. Create `ledger/LedgerEntry.java` (record with all columns; `isCharge()`, `isPayment()`, `isReversal()` helpers).
9. Create `ledger/LedgerStore.java` (JdbcClient): `insertEntry`, `findEntryById(wid,aid,entryId)`, `findChargeById(wid,aid,chargeId)` (fetches the CHARGE entry whose `id = charge_id`), `lockChargeRow(wid,aid,chargeId)` (SELECT…FOR UPDATE scoped by wid+aid), `computeOutstanding(wid,aid,chargeId)` (SUM of amount_cents where charge_id=:cid), `countTotal(wid,aid)`, `findByParty(wid,aid,partyId,page,size)`, `findChargesPage(wid,aid,partyId?,status?,page,size)`, `computePartySummary(wid,aid,partyId)`.
10. Add `lockPartyRow(wid,aid,partyId)` to `PartyStore.java` (SELECT id FROM parties WHERE id=:pid AND workspace_id=:wid AND app_id=:aid FOR UPDATE).
11. Check: `cd backend && mvn test` — all tests pass.

### Slice 3 — Service
12. Create the four exception classes in `com.followwise.ledger`.
13. Create `ledger/LedgerService.java` injecting `LedgerStore`, `PartyStore`, `AppStore`, `AccessService`, `Clock`, `@Value("${app.max-ledger-entries-per-app:20000}")`. Methods: `createCharge`, `recordPayment`, `reverseEntry`, `getPartyLedger`, `listCharges`, `getCharge`. Business rules per D-81–D-85: integral-check using `!node.isFloatingPointNumber() && node.isIntegralNumber() && node.canConvertToLong()`; "today" = `LocalDate.now(clock.withZone(ZoneId.of(appTimezone)))`; idempotency key parsed/checked; app-row lock via `PartyStore.lockApp`; party-row lock via `PartyStore.lockPartyRow`; charge-row lock via `LedgerStore.lockChargeRow`.
14. Check: `cd backend && mvn test` — all tests pass.

### Slice 4 — Controller, parser, exception handler, body size
15. Create `ledger/LedgerPayloadParser.java` (strict JSON, reuses `AppPayloadParser.parseStrictJson`): `parseCreateCharge`, `parseRecordPayment`, `parseReverseEntry`; allowed keys enforced; rejects unknown envelope keys.
16. Create `ledger/LedgerExceptionHandler.java` (`@RestControllerAdvice`) mapping the four ledger exception types to 400/404/409/422 ProblemDetail (same pattern as `PartyExceptionHandler`).
17. Create `ledger/LedgerController.java` with all six endpoints from D-82; extract `Idempotency-Key` header and pass to service; path: `/api/workspaces/{wid}/apps/{aid}`.
18. Extend `BodySizeFilter.isDataTarget` regex to also match ledger write paths: POST `.../parties/{id}/charges`, POST `.../charges/{id}/payments`, POST `.../ledger/{id}/reverse` → limit 8192 bytes.
19. Add `app.max-ledger-entries-per-app=${MAX_LEDGER_ENTRIES_PER_APP:20000}` to `application.properties`; add `MAX_LEDGER_ENTRIES_PER_APP=20000` to `.env.example`.
20. Check: `cd backend && mvn test` — all tests pass.

### Slice 5 — Tests and docs
21. Create `backend/src/test/java/com/followwise/ledger/LedgerApiTest.java` covering all required tests listed in Checks below. Add ledger entry cleanup to `AppApiTest.setUp()` (DELETE FROM ledger_entries before DELETE FROM parties/records).
22. Append D-80–D-85 verbatim to README.md section 14.1.
23. Update `docs/STATUS.md`: split "1d. Charges and ledger" into "1d-i. Ledger core (PLAN-0010)" and "1d-ii. Charge generation (PLAN-0011)"; tick 1d-i; add log line; set Next task to `PLAN-0011: charge generation (Phase 1, step 1d-ii)`.
24. Check: `cd backend && mvn test` — all tests pass; count reported in Execution report.

## Checks (how to prove it works)
- After Slice 1: Flyway output shows V7 applied; `cd backend && mvn test` green.
- After each slice: `cd backend && mvn test` green.
- After Slice 5 (`LedgerApiTest`), minimum test coverage:
  - Charge + partial payments gives correct outstanding and status open
  - Exact payoff gives status paid; outstanding 0
  - Overpayment by 1 cent rejected 409 payment_exceeds_outstanding
  - Payment and reversal sign rules (CHARGE positive, PAYMENT negative, REVERSAL negates target)
  - Reversing a payment restores outstanding
  - Reversing a charge only when no active payments (outstanding = original amount)
  - Double reversal rejected (unique constraint)
  - Reversal of a reversal rejected
  - Amount edge cases: 0, negative, 100.0 (float), string, boolean rejected; 100,000,000,000 allowed; 100,000,000,001 rejected; long overflow
  - paid_on future rejected; today allowed; 2000-01-01 allowed; 1999-12-31 rejected
  - due_date boundaries (5 years before and after today)
  - Clock test: two apps in opposite time zones; "due today" in one is overdue in the other proves app-zone use
  - Archived party blocks new charges 409; allows payments and reversals
  - Archived app blocks writes 409; allows reads 200
  - scheduleId rules (non-archived chargeSchedule of correct partyType)
  - Id scoping: entry/charge/party from another app or workspace → 404 on every endpoint
  - Permission matrix: OWNER all; STAFF RECORD_PAYMENT and VIEW_LEDGER, 403 on MANAGE_LEDGER; PORTAL_USER 403; non-member 404
  - Idempotency replay creates one entry; same key different body → 409; malformed key → 400
  - Concurrent payments of 60% each on one charge: exactly one success
  - Concurrent reversals of same entry: exactly one success
  - Trigger rejects direct UPDATE and DELETE via jdbcClient SQL
  - Summary and balance correct with mixed entries
  - List filters (status, partyId) and pagination and ordering
  - Entry limit 409 limit_exceeded
  - Body over 8 KB and chunked over 8 KB → 413
  - Duplicate JSON key and unknown envelope key rejected
  - No echo of SECRET_MARKER_123 from label, note, reason in any error
  - No stack traces in error responses
  - V7 applies on top of V6

## Acceptance criteria
- [ ] V7 migration applies cleanly; trigger blocks UPDATE and DELETE.
- [ ] VIEW_LEDGER, RECORD_PAYMENT, MANAGE_LEDGER in Permission.java.
- [ ] Clock bean injectable; test can override with Clock.fixed.
- [ ] POST charge → 201; GET party ledger → 200 with correct summary.
- [ ] Outstanding computed correctly from entries.
- [ ] Overpayment rejected; exact payoff → status paid.
- [ ] Reversal of a reversal rejected.
- [ ] Idempotency replay → original entry, no duplicate.
- [ ] Concurrent payment race → exactly one succeeds.
- [ ] D-80–D-85 appended to README section 14.1.

## Risks and assumptions
- Assumption: the app's timezone string is stored in the current definition at `definition.timezone` (same field used by existing templates); if the field path differs, block and report.
- Assumption: `AppStore.findAppByIdAndWorkspaceId` already returns archived status; no new store method needed.
- Assumption: existing `PartyStore.lockApp` (SELECT…FOR UPDATE) already in a `@Transactional` service method; charge create locks party row in the same transaction.
- TRUNCATE needs a separate BEFORE STATEMENT trigger because row-level triggers do not fire for TRUNCATE.
- Jackson 3: `isIntegralNumber()` returns false for `100.0` and `1e3`; use `!node.isFloatingPointNumber() && node.isIntegralNumber() && node.canConvertToLong()`.

## Decisions needed
None. All decisions are pre-approved as D-80–D-85.

## Decisions block (executor appends to README 14.1 verbatim)
| ID | Decision |
|---|---|
| D-80 | Persistence (Flyway V7, JdbcClient, no JPA, RESTRICT foreign keys): one table ledger_entries(id uuid PK, workspace_id, app_id, party_id, charge_id uuid not null, entry_type CHARGE\|PAYMENT\|REVERSAL, amount_cents bigint not null, currency char(3) not null default 'USD', kind text null (recurring\|late_fee\|manual, CHARGE only), schedule_id text null, period_key text null, label text null, due_date date null (CHARGE only), paid_on date null (PAYMENT only), note text null, reverses_entry_id uuid null unique, idempotency_key uuid null, request_hash char(64) null, created_by, created_at). A CHARGE entry has charge_id equal to its own id; PAYMENT and REVERSAL entries carry the charge_id of the charge they belong to. Signed amounts: CHARGE positive, PAYMENT negative, REVERSAL is the exact negation of the entry it reverses. CHECKs: amount_cents <> 0 and absolute value at most 100,000,000,000; sign matches type for CHARGE and PAYMENT; currency = 'USD'; the type-specific columns are null for other types. Unique (app_id, idempotency_key) where not null. Unique (app_id, party_id, schedule_id, period_key) where entry_type = 'CHARGE' and period_key is not null (for PLAN-0011). A database trigger raises an exception on every UPDATE and DELETE of ledger_entries. Balances are never stored: a charge's outstanding amount is the sum of amount_cents over entries with that charge_id; a party's balance is the sum over its entries. |
| D-81 | Business rules, one fixed-text error per problem, never echoing submitted values: amounts are JSON integers in cents (reject decimals, strings, booleans, zero, negatives, and values above 100,000,000,000). A payment amount must not exceed the charge's current outstanding amount (no overpayment, 409 payment_exceeds_outstanding). paid_on is a real ISO date, not after today in the app's time zone, and not before 2000-01-01. due_date is within 5 years before and after today. label is 1 to 120 characters and note at most 200, with no control characters and no angle brackets. Reversal needs a reason of 1 to 200 characters. A PAYMENT can be reversed if it is not already reversed. A CHARGE can be reversed only if it is not already reversed and its outstanding amount equals its original amount (no active payments). A REVERSAL entry can never be reversed (post a new entry instead). Charges cannot be created for archived parties (409 party_archived); payments and reversals on archived parties are allowed. Writes on an archived app return 409 app_archived. scheduleId on a manual charge is optional; if given it must be a non-archived chargeSchedule of the app's current definition whose partyType equals the party's type. |
| D-82 | Endpoints under /api/workspaces/{wid}/apps/{aid}: POST .../parties/{partyId}/charges {label, amountCents, dueDate, scheduleId?} (kind manual) returns 201; POST .../charges/{chargeId}/payments {amountCents, paidOn, note?} returns 201; POST .../ledger/{entryId}/reverse {reason} returns 201 with the REVERSAL entry; GET .../parties/{partyId}/ledger?page=&size= returns entries ordered by created_at then id plus a summary {totalChargedCents, totalPaidCents, balanceCents}; GET .../charges?status=open\|paid\|overdue\|all&partyId=&page=&size= returns charges with outstandingCents and a derived status; GET .../charges/{chargeId} returns the charge with its entries. Derived status: reversed (the charge is reversed), paid (outstanding 0), overdue (outstanding > 0 and due_date before today in the app's time zone), open. New permissions: VIEW_LEDGER (OWNER, STAFF), RECORD_PAYMENT (OWNER, STAFF), MANAGE_LEDGER (OWNER only, for manual charges and reversals). PORTAL_USER gets 403, non-members 404 (D-42). Every query scopes by workspace_id AND app_id (an id from another app, party, or workspace is a 404). Pagination default 20, maximum 50. |
| D-83 | Concurrency and idempotency: every ledger write runs in one transaction that first locks the CHARGE entry row of the charge concerned (SELECT ... FOR UPDATE) and computes outstanding inside that transaction, so two simultaneous payments can never exceed the charge. Creating a charge locks the party row. Optional Idempotency-Key header (a UUID) on all three write endpoints: a replay with the same key and the same request hash returns the original entry with its original status and creates nothing; the same key with a different request hash returns 409 idempotency_conflict; a malformed key is 400. The request hash is SHA-256 of the canonical request fields plus the endpoint path. |
| D-84 | Time handling: "today" always comes from an injectable java.time.Clock bean evaluated in the app's time zone from the current definition (never the server zone). Timestamps are stored in UTC. Money in responses is always integer cents plus the currency code, never a decimal or a formatted string. |
| D-85 | Limits and guards: MAX_LEDGER_ENTRIES_PER_APP (env, default 20000) returns 409 limit_exceeded; ledger write bodies are limited to 8 KB by the existing counting filter (extend its per-path rules); raw-body strict parsing and fixed-text problem+json from D-72 and D-73 apply. The immutability trigger from D-80 is covered by a test that attempts a direct UPDATE and DELETE and expects failure. |

## STATUS.md update (executor does this)
- Split "- [ ] 1d. Charges and ledger" into:
  - "- [x] 1d-i. Ledger core (PLAN-0010)"
  - "- [ ] 1d-ii. Charge generation (PLAN-0011)"
- Task log line: `| YYYY-MM-DD | PLAN-0010: ledger core (Phase 1, step 1d-i) | <model> | Done |`
- Update "Current step" to `Phase 1, step 1d-ii` and "Next task" to `PLAN-0011: charge generation (Phase 1, step 1d-ii)`.

---
## Orchestrator amendments (binding, override anything above that conflicts)
A. Run no git command. The plan's Decisions block (D-80 to D-85) stays verbatim; also append D-86 (below).
B. Test cleanup: the immutability trigger and RESTRICT foreign keys block normal cleanup. Create ONE test-only helper (src/test only, never in main code) that opens a connection, runs SET session_replication_role = replica, deletes in dependency order (ledger_entries first, then parties and records, then the existing tables), and resets the role. Use it in the setUp or cleanup of every existing test class that deletes parties, apps, workspaces or users (AppApiTest, PartyApiTest, and any other). Replace Slice 5 step 21 with this.
C. Schema hardening in V7 (in addition to D-80):
 - parties gets UNIQUE (id, workspace_id, app_id).
 - ledger_entries gets UNIQUE (id, workspace_id, app_id, party_id); FOREIGN KEY (party_id, workspace_id, app_id) REFERENCES parties(id, workspace_id, app_id); FOREIGN KEY (charge_id, workspace_id, app_id, party_id) REFERENCES ledger_entries(id, workspace_id, app_id, party_id); FOREIGN KEY (reverses_entry_id, workspace_id, app_id, party_id) REFERENCES ledger_entries(id, workspace_id, app_id, party_id); plain FKs to workspaces, apps and users. All RESTRICT.
 - CHECKs: CHARGE requires charge_id = id, kind, label and due_date not null, paid_on and reverses_entry_id null; PAYMENT requires paid_on not null and kind, schedule_id, period_key, label, due_date, reverses_entry_id null; REVERSAL requires reverses_entry_id not null and the CHARGE and PAYMENT-only columns null.
 - created_at DEFAULT clock_timestamp() and is never supplied by the Clock bean.
 - The existing immutability triggers (UPDATE or DELETE rows, and TRUNCATE statement) stay.
D. Lock order in every ledger write, inside one transaction: app row (SELECT ... FOR UPDATE, 409 app_archived when archived), then party row (create charge only), then charge row (payment and reverse). The entry limit is counted after the app lock.
E. Check order for writes: strict envelope parse (400), Idempotency-Key format (400), membership (404), permission (403), app exists (404), app archived (409), resource lookups scoped by workspace_id and app_id (404; malformed UUID path variables are a fixed-text 404), idempotency lookup, business rules (409 or 422), insert.
F. Idempotency: after the locks, look up (app_id, key). Same key, same hash, same user returns the original entry with its original status; different hash or different user returns 409 idempotency_conflict. Also catch the unique violation on insert (concurrent same-key requests), re-read, and apply the same rule. Hash = SHA-256 over userId, HTTP method, path, and canonical request fields.
G. Money SQL: COALESCE(SUM(amount_cents), 0)::bigint everywhere. Derived status is computed in SQL with today (app zone) as a parameter, in this order: reversed, then paid (outstanding = 0 and not reversed), then overdue (outstanding > 0, due_date < today), then open. List filters: open = outstanding > 0, not reversed, due_date >= today; overdue = outstanding > 0, not reversed, due_date < today; paid = outstanding = 0, not reversed; all = everything including reversed. A charge due today is open, not overdue.
H. Time: the Clock bean is used only for "today" in the app time zone, read from the top-level "timezone" of the CURRENT definition version. Never use the Clock for created_at. LedgerApiTest imports a test-only mutable Clock bean (@Primary, with a setter) so tests can set the instant; other test classes keep the real clock.
I. Slice 0: replace EVERY String.toLowerCase() without a locale in backend/src/main with toLowerCase(Locale.ROOT) (find them by searching the source; the stores have four occurrences). Add the planned test.
J. Immutability tests: UPDATE, DELETE and TRUNCATE each fail (separate transactions) with the trigger message. Also direct-SQL tests that insert (1) a payment with a positive amount, (2) an entry whose charge_id belongs to another app, (3) a reversal pointing at another party's entry; each must fail at the database.
K. Reuse com.followwise.party.PageResponse; do not duplicate it.
L. Extra tests: concurrent same Idempotency-Key requests give exactly one entry and two identical successes; different user with the same key gets 409; list status filters per G including a charge due today and a reversed charge; entry limit is exact under two concurrent creates at limit minus one.
M. In the report list every deviation. STATUS log line: | YYYY-MM-DD | PLAN-0010: ledger core (Phase 1, step 1d-i) | <model> | Done |
N. Also append to README 14.1 after D-85:
D-86 Ledger integrity is also enforced by the database: composite foreign keys bind each entry, its charge, and any reversal target to the same workspace, app, and party; CHECKs fix the columns each entry type may carry; created_at comes from the database. Ledger writes lock in the order app, party, charge. Tests clean the ledger only through a test-only helper that sets session_replication_role = replica; main code never bypasses the immutability trigger.
---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks:
