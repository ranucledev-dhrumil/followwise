# PLAN-0009: Parties and records with custom fields

Status: DRAFT
Phase / step: Phase 1, step 1c
Author: Claude (Gemini 2.5 Pro via Antigravity)   Executor: (model that executed)
Branch: plan-0009 (created by the founder; AI models run no git)

## Goal
Persist parties (clients/tenants) and records (units) for an app, with custom field values validated against the app's current definition. Expose a workspace-scoped REST API; no frontend, no ledger, no documents, no reminders, no portal.

## Out of scope
- Ledger, charges, documents, reminders, portal access, frontend.
- Soft-deletes of field data or retroactive re-validation when the definition changes.

## Inputs
- README sections 6, 8.3, 8.4, 14.2 and decision rows D-41, D-42, D-62–D-73 (already in context).
- `backend/src/main/java/com/followwise/app/AppStore.java` and siblings (already in context).
- `backend/src/main/resources/appdefinition/app-definition-v1.schema.json` (already in context).
- `backend/src/main/java/com/followwise/workspace/Permission.java`, `AccessService.java` (already in context).

## VERIFY results
- **V1 — Case-insensitive prefix search:** Use `WHERE lower(display_name) LIKE :pattern ESCAPE '\'` with `pattern = escapedPrefix.toLowerCase() + "%"` (escape `\`, `%`, `_` in Java first). The migration index `(app_id, party_type, lower(display_name))` supports this. `ILIKE` does **not** use a `lower()` expression index.
- **V2 — Email validation:** Use a static compiled `Pattern` from `java.util.regex`: `^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$`. No new dependency; `jakarta.mail` is not on the classpath.

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| `backend/src/main/resources/db/migration/V6__parties_records.sql` | Create | Tables, indexes, CHECKs |
| `backend/src/main/java/com/followwise/workspace/Permission.java` | Edit | Add VIEW_DATA, EDIT_DATA |
| `backend/src/main/java/com/followwise/party/FieldValidator.java` | Create | Per-field value validation |
| `backend/src/main/java/com/followwise/party/PartyStore.java` | Create | JdbcClient CRUD for parties |
| `backend/src/main/java/com/followwise/party/PartyService.java` | Create | Business logic, permissions |
| `backend/src/main/java/com/followwise/party/PartyController.java` | Create | REST endpoints for parties |
| `backend/src/main/java/com/followwise/party/RecordStore.java` | Create | JdbcClient CRUD for records |
| `backend/src/main/java/com/followwise/party/RecordService.java` | Create | Business logic, permissions |
| `backend/src/main/java/com/followwise/party/RecordController.java` | Create | REST endpoints for records |
| `backend/src/main/java/com/followwise/party/PartyExceptionHandler.java` | Create | Maps party/record exceptions |
| `backend/src/main/java/com/followwise/party/PartyRecord.java` | Create | Internal row record |
| `backend/src/main/java/com/followwise/party/RecordRow.java` | Create | Internal row record |
| `backend/src/main/java/com/followwise/party/PartyResponse.java` | Create | API response |
| `backend/src/main/java/com/followwise/party/RecordResponse.java` | Create | API response |
| `backend/src/main/java/com/followwise/party/PartySummaryResponse.java` | Create | List response |
| `backend/src/main/java/com/followwise/party/RecordSummaryResponse.java` | Create | List response |
| `backend/src/main/java/com/followwise/app/BodySizeFilter.java` | Edit | Add party/record POST and PUT patterns |
| `backend/src/main/resources/application.properties` | Edit | Add party/record limits |
| `.env.example` | Edit | Add MAX_PARTIES_PER_APP, MAX_RECORDS_PER_APP |
| `backend/src/test/java/com/followwise/PartyApiTest.java` | Create | Integration tests |
| `README.md` | Edit | Append D-74–D-79 to section 14.1 |

## Steps

### Slice 1 — Migration V6 and permissions
1. Create `V6__parties_records.sql`:
   ```sql
   CREATE TABLE parties (
     id uuid PRIMARY KEY,
     workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
     app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
     party_type text NOT NULL,
     display_name text NOT NULL,
     data jsonb NOT NULL,
     definition_version int NOT NULL,
     version int NOT NULL DEFAULT 1,
     created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
     updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
     created_at timestamptz NOT NULL,
     updated_at timestamptz NOT NULL,
     archived_at timestamptz,
     CONSTRAINT parties_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 200),
     CONSTRAINT parties_version_pos CHECK (version >= 1),
     CONSTRAINT parties_data_obj CHECK (jsonb_typeof(data) = 'object')
   );
   CREATE INDEX idx_parties_app_type_name ON parties (app_id, party_type, lower(display_name));
   CREATE INDEX idx_parties_workspace ON parties (workspace_id);

   CREATE TABLE records (
     id uuid PRIMARY KEY,
     workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
     app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
     record_type text NOT NULL,
     display_name text NOT NULL,
     data jsonb NOT NULL,
     definition_version int NOT NULL,
     version int NOT NULL DEFAULT 1,
     created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
     updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
     created_at timestamptz NOT NULL,
     updated_at timestamptz NOT NULL,
     archived_at timestamptz,
     CONSTRAINT records_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 200),
     CONSTRAINT records_version_pos CHECK (version >= 1),
     CONSTRAINT records_data_obj CHECK (jsonb_typeof(data) = 'object')
   );
   CREATE INDEX idx_records_app_type_name ON records (app_id, record_type, lower(display_name));
   CREATE INDEX idx_records_workspace ON records (workspace_id);
   ```
2. Add `VIEW_DATA(OWNER, STAFF)` and `EDIT_DATA(OWNER)` to `Permission.java`.
3. Check: `cd backend && mvn test` — 118 tests pass, Flyway applies V6.

### Slice 2 — FieldValidator
4. Create `com.followwise.party.FieldValidator` with a single public method `List<ValidationError> validate(JsonNode typeNode, JsonNode data)` where `typeNode` is the partyType or recordType node from the parsed definition. Rules (collect up to 20 errors, path `/data/{fieldId}`):
   - Required field present and non-null.
   - `text`: string, length 1–1000.
   - `email`: string, matches static compiled regex `^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$`, max 254 chars.
   - `phone`: string, 1–30 chars, matches `^[+0-9()\-\s]{1,30}$`.
   - `date`: string matching `\d{4}-\d{2}-\d{2}` and parseable by `LocalDate.parse`.
   - `integer`: integral number.
   - `money`: integral number >= 0.
   - `boolean`: boolean.
   - `select`: string matching one non-archived option id in the field definition.
   - `party_ref`: string UUID format (parseable by `UUID.fromString`); cross-reference existence check deferred to future plan (noted in risks).
   - `record_ref`: same as party_ref.
   - Unknown keys in `data` that have no field id in the type are rejected (error at `/data`).
   - Archived fields: values for archived fields are accepted silently (no validation error).
5. Check: `cd backend && mvn test` — still 118 tests pass (unit test not yet written; will be in Slice 6).

### Slice 3 — PartyStore and RecordStore
6. Create `PartyRecord` (Java record: id, workspaceId, appId, partyType, displayName, dataJson, definitionVersion, version, createdBy, updatedBy, createdAt, updatedAt, archivedAt).
7. Create `RecordRow` (same shape with recordType).
8. Create `PartyStore` with JdbcClient methods mirroring AppStore patterns:
   - `countActive(appId, workspaceId, partyType)` — scoped by workspace_id AND app_id.
   - `insert(...)` — `CAST(:data AS jsonb)`.
   - `findById(id, appId, workspaceId)` — returns `Optional<PartyRecord>`, `data::text`.
   - `findAll(appId, workspaceId, partyType, namePrefix optional, includeArchived, limit 100)` — `lower(display_name) LIKE :pattern ESCAPE '\'`.
   - `update(id, appId, workspaceId, expectedVersion, displayName, dataJson, updatedBy, now)` — optimistic locking via `AND version = :expected AND archived_at IS NULL`, returns rowsAffected.
   - `archive(id, appId, workspaceId, updatedBy, now)` — returns rowsAffected.
9. Create `RecordStore` identically, targeting the `records` table with `record_type`.
10. Check: `cd backend && mvn test` — 118 tests pass.

### Slice 4 — PartyService and RecordService
11. Create `PartyService` with `@Transactional` methods:
    - `create(workspaceId, appId, userId, partyType, displayName, data)`: `requirePermission(EDIT_DATA)`, load app (must be active), load current definition version, validate partyType exists and not archived, run `FieldValidator`, check count <= `party.max-per-app` (default 2000), insert, return response.
    - `list(workspaceId, appId, userId, partyType, namePrefix, includeArchived)`: `requirePermission(VIEW_DATA)`.
    - `get(workspaceId, appId, userId, id)`: `requirePermission(VIEW_DATA)`.
    - `update(workspaceId, appId, userId, id, expectedVersion, displayName, data)`: `requirePermission(EDIT_DATA)`, re-validate against current definition; on 0 rows throw conflict.
    - `archive(workspaceId, appId, userId, id)`: `requirePermission(EDIT_DATA)`.
12. Create `RecordService` identically for records.
13. Add `party.max-per-app=2000` and `record.max-per-app=2000` to `application.properties`.
14. Add `MAX_PARTIES_PER_APP=2000` and `MAX_RECORDS_PER_APP=2000` to `.env.example`.
15. Check: `cd backend && mvn test` — 118 tests pass.

### Slice 5 — Controllers, exception handler, body-size filter
16. Create `PartyController` at `/api/workspaces/{workspaceId}/apps/{appId}/parties`:
    - `POST /` — `@RequestBody byte[] rawBody`, strict parse (reuse `AppPayloadParser.parseStrictJson`), allowed keys: `partyType`, `displayName`, `data`. Returns 201.
    - `GET /` — query params: `partyType` (required), `namePrefix` (optional, max 100 chars), `archived` (default false). Returns 200 list.
    - `GET /{partyId}` — 200 or 404.
    - `PUT /{partyId}` — `@RequestBody byte[] rawBody`, allowed keys: `expectedVersion`, `displayName`, `data`. Returns 200.
    - `POST /{partyId}/archive` — 200.
17. Create `RecordController` identically at `.../records`.
18. Extend `BodySizeFilter` regex to match the two new POST and PUT patterns (parties and records).
19. Create `PartyExceptionHandler` (`@RestControllerAdvice`) reusing the same ProblemDetail pattern from `AppExceptionHandler`. Map: `PartyNotFoundException` → 404, `PartyConflictException` → 409, `PartyValidationException` → 422, `PartyBadRequestException` → 400.
20. Create the four exception classes in `com.followwise.party`.
21. Check: `cd backend && mvn test` — 118 tests pass.

### Slice 6 — Integration tests and docs
22. Create `PartyApiTest` (Testcontainers, @SpringBootTest) covering the required test list (see Acceptance criteria). Minimum 20 new tests.
23. Append D-74–D-79 verbatim to README.md section 14.1 (decision log table).
24. Check: `cd backend && mvn test` — all tests pass (target ≥ 138).

## Checks (how to prove it works)
- `cd backend && mvn test` — green after each slice.
- After Slice 1: Flyway output shows V6 applied.
- After Slice 6: test count reported in Execution report.

## Acceptance criteria
- [ ] V6 migration applies cleanly.
- [ ] `VIEW_DATA` and `EDIT_DATA` present in Permission enum.
- [ ] POST party with valid data → 201; GET → 200 with correct fields.
- [ ] PUT party with correct expectedVersion → 200; with stale version → 409.
- [ ] POST party with required field missing → 422 with `/data/{fieldId}` error path.
- [ ] POST party with invalid email → 422.
- [ ] POST party with unknown field in data → 422.
- [ ] GET list with namePrefix filters case-insensitively.
- [ ] Party from another workspace/app → 404.
- [ ] Non-owner → 403 on write; STAFF → 200 on read.
- [ ] Archived app → 422 or 409 on new party.
- [ ] Records: same set of behaviours as parties.
- [ ] D-74–D-79 appended to README section 14.1.

## Risks and assumptions
- **party_ref / record_ref cross-reference existence** not checked in this plan (noted as known gap, deferred to a future slice when both are populated).
- **Display-name uniqueness**: not enforced (D-75 does not require it; duplicates are allowed).
- **Archived type**: creating a party for an archived partyType is rejected (503-safe default; can be relaxed later if needed).
- Assumption: `AppPayloadParser` is injected into party controllers for `parseStrictJson`; it is a `@Component` with no app-specific state.
- Assumption: `AppStore.findAppByIdAndWorkspaceId` and `AppStore.findVersion` are reused by `PartyService` to load the current definition (no new store method needed for this).

## Decisions needed
None. All decisions are pre-approved as D-74–D-79.

## Decisions block (executor appends to README 14.1 verbatim)
| ID | Decision |
|---|---|
| D-74 | Persistence (Flyway V6, JdbcClient, no JPA): parties(id uuid PK, workspace_id, app_id, party_type text, display_name text not null, data jsonb not null, definition_version int not null, version int not null default 1, created_by, updated_by, created_at, updated_at, archived_at null) and records with the same shape (record_type). Foreign keys use the default RESTRICT, never CASCADE. Every row carries workspace_id and app_id and every query scopes by workspace_id AND app_id (a party or record id from another app or workspace is a 404). Indexes on (app_id, party_type, lower(display_name)) and (app_id, record_type, lower(display_name)). CHECKs: display_name 1 to 200 characters, version >= 1, jsonb is an object. No hard deletes. |
| D-75 | Value rules, enforced in Java on every write against the app's current definition, one error per field with path /data/{fieldId} (at most 20 errors, then stop): text 1–1000 chars; email regex ^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$ max 254 chars; phone 1–30 chars ^[+0-9()\-\s]{1,30}$; date string YYYY-MM-DD parseable by LocalDate; integer/money integral number (money >= 0); boolean; select must match a non-archived option id; party_ref/record_ref must be a valid UUID string (cross-app/workspace existence deferred). Unknown keys in data are rejected. Archived fields accept any value silently. No new dependency; plain java.util.regex. |
| D-76 | Endpoints: POST /api/workspaces/{wid}/apps/{aid}/parties {partyType, displayName, data} → 201; GET with ?partyType (required), ?namePrefix (optional, max 100 chars, case-insensitive prefix on lower(display_name) LIKE with ESCAPE), ?archived (default false) → 200 list; GET /{partyId} → 200; PUT /{partyId} {expectedVersion, displayName, data} → 200 optimistic-locked; POST /{partyId}/archive → 200. Same shape at .../records with recordType. Permissions: VIEW_DATA (Owner, Staff) for reads; EDIT_DATA (Owner) for writes. Non-member → 404; insufficient role → 403. |
| D-77 | Limits (env-configurable): max parties per app 2000 (MAX_PARTIES_PER_APP), max records per app 2000 (MAX_RECORDS_PER_APP). Scoped by app_id only (not by party_type). Payload body limit is 80 KB (reuses BodySizeFilter at 81920 bytes). |
| D-78 | Optimistic locking on update: the PUT request carries expectedVersion; the UPDATE WHERE version = :expected AND archived_at IS NULL returns 0 rows on mismatch → 409 conflict. Version increments by 1 on each successful update. No separate audit table in v1. |
| D-79 | The party package is com.followwise.party. It reuses AppPayloadParser (parseStrictJson), AppStore (to load app + current definition), AccessService, and ValidationError from appdef. PartyExceptionHandler maps the four party exception types using the same ProblemDetail pattern as AppExceptionHandler. |

## STATUS.md update (executor does this)
- Tick `- [ ] 1c. Parties and records with custom fields`.
- Task log line: `| YYYY-MM-DD | PLAN-0009: parties and records (Phase 1, step 1c) | <model> | Done |`
- Update "Current step" to `Phase 1, step 1d` and "Next task" to `PLAN-0010: charges and ledger (Phase 1, step 1d)`.

---

## Orchestrator amendments (binding, override anything above that conflicts)
A. Run no git command. Ignore the plan's own "Decisions block", its VERIFY V1 index claim, and its decision wording. The decisions below replace them; append D-74 to D-79 to README 14.1 exactly as written here (table format used by earlier rows).

D-74 Persistence (Flyway V6, JdbcClient, no JPA): parties(id uuid PK, workspace_id, app_id, party_type text, display_name text not null, data jsonb not null, definition_version int not null, version int not null default 1, created_by, updated_by, created_at, updated_at, archived_at null) and records with the same shape (record_type). Foreign keys use the default RESTRICT, never CASCADE. Every row carries workspace_id and app_id and every query scopes by workspace_id AND app_id (a party or record id from another app or workspace is a 404). Indexes on (app_id, party_type, lower(display_name)) and (app_id, record_type, lower(display_name)). CHECKs: display_name 1 to 200 characters, version >= 1, jsonb is an object, octet_length(data::text) <= 16384. No hard deletes.
D-75 Value rules, enforced in Java on every write against the app's current definition, one error per field with path /data/{fieldId} (at most 20 errors, fixed message text, never echoing the submitted value). Strings are trimmed first; empty after trimming counts as missing. Unknown field ids are rejected. Archived fields are read-only: their stored values are carried over unchanged on update and a different submitted value is an error. Required fields must be present and non-empty on create and cannot be cleared on update. text: 1 to 500 chars, no control characters. email: simple syntax check, at most 254 chars, stored lowercase. phone: digits, spaces, +, -, (, ) only, 5 to 32 chars. date: ISO yyyy-MM-dd and a real calendar date. integer: JSON integer between -1,000,000,000 and 1,000,000,000. money: JSON integer cents between 0 and 100,000,000,000 (never a decimal). boolean: JSON true or false. select: an existing, non-archived option id. party_ref and record_ref: the UUID of an existing party or record of the field's target type in the SAME app and workspace; archived targets are rejected for new or changed references (an unchanged reference to a since-archived target is kept). Serialized data is at most 16 KB.
D-76 display_name is computed on every write and never accepted from the client: the first 200 characters of the value of the first non-archived text-type field in definition order (tenant: name; unit: address; client: name). If the type has no such field with a value, the write fails with a fixed error. The row also stores definition_version (the version validated against).
D-77 Endpoints under /api/workspaces/{wid}/apps/{aid}: POST .../parties {type, data}; GET .../parties?type=&q=&archived=&page=&size= (default size 20, maximum 50, ordered by lower(display_name), id; response {items, page, size, total}); GET, PUT {data, expectedVersion}, POST .../archive and POST .../restore for .../parties/{id}; the same set for .../records. PUT replaces the whole data object (archived-field values are carried over). Permissions VIEW_DATA and EDIT_DATA, both OWNER and STAFF; PORTAL_USER gets 403, non-members 404 (D-42). Writes on an archived app return 409 app_archived; reads still work. A stale expectedVersion returns 409 stale_version with the current version; updates use one conditional UPDATE on version, workspace_id, app_id and archived_at.
D-78 Limits (env vars): MAX_PARTIES_PER_APP=500 and MAX_RECORDS_PER_APP=500 (properties app.max-parties-per-app and app.max-records-per-app). A create requires non-archived rows < limit and total rows including archived < 2 x limit, otherwise 409 limit_exceeded. The insert path first locks the app row (SELECT id FROM apps WHERE id=:aid AND workspace_id=:wid AND archived_at IS NULL FOR UPDATE, 409 app_archived if no row is locked) so concurrent creates cannot exceed the limit. q is a case-insensitive prefix match (lower(display_name) LIKE :p ESCAPE '\') with \, % and _ escaped, at most 100 chars; page is zero-based.
D-79 Archived parties and records stay readable and keep existing references, but cannot be referenced by new or changed writes. The raw-body strict parsing, a counting size filter limited to 32 KB for these endpoints, and fixed-text problem+json errors from D-72 and D-73 apply to every new endpoint.

B. Request and response shapes: create is {type, data}; update is {data, expectedVersion}; there is NO displayName field anywhere (unknown envelope keys, including displayName, are rejected). List params are type, q, archived, page, size. Responses include id, type, displayName, data, version, definitionVersion, archived (boolean), createdAt, updatedAt. Add restore endpoints. Archive and restore bump version.
C. Permissions: VIEW_DATA and EDIT_DATA are both (OWNER, STAFF). Fix the Permission.java step and the acceptance criteria and tests (STAFF can write; PORTAL_USER is 403 everywhere; non-member 404).
D. References: FieldValidator takes a small RefChecker interface (implemented with a store lookup scoped by workspace_id AND app_id and the target type) and implements D-75 fully now. Remove the "deferred" risk.
E. Props and env: app.max-parties-per-app, app.max-records-per-app, MAX_PARTIES_PER_APP=500, MAX_RECORDS_PER_APP=500 in .env.example.
F. BodySizeFilter: add a per-path limit. Party and record POST/PUT: 32768 bytes (Content-Length plus the counting wrapper). Existing app endpoints keep 81920.
G. Archived app writes are 409 app_archived (never 422). Creating or updating with an archived party or record type in the definition is a 422 with a fixed error.
H. Tests go under backend/src/test/java/com/followwise/party/. Required: a valid/invalid matrix for all ten field types and each D-75 boundary (500/501 chars, 4/5 and 32/33 phone chars, integer and money bounds, decimal money, lowercase email, impossible dates); required, unknown-field, archived-field carry-over and archived-option rules; reference rules (wrong target type, other app, other workspace, archived target, unchanged archived reference kept, wrong id format); display_name rules including recompute on update and 200-char truncation; RPM tenant with a unit reference, unit, CDT client; pagination (default 20, max 50), ordering, prefix search, and % _ \ escaping; permission matrix on every endpoint (OWNER and STAFF write, PORTAL_USER 403, non-member 404); id from another app or workspace is 404; concurrent updates with the same expectedVersion give one success; concurrent creates at limit minus one give exactly one success; limit_exceeded including the 2 x limit total; archived app write 409 and read 200; archive and restore; definition_version recorded; duplicate JSON key rejected; chunked body over 32 KB gives 413; the 20-error cap; no echo of SECRET_MARKER_123 in any error; error bodies have no stack trace; V6 applies on top of V5.
I. STATUS log line: | YYYY-MM-DD | PLAN-0009: parties and records (Phase 1, step 1c) | <model> | Done |
J. In the report list every deviation and every rule that could not be implemented.

---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks:
