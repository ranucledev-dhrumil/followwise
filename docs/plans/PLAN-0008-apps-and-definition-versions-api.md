# PLAN-0008: Apps and definition versions API

Status: APPROVED
Phase / step: Phase 1, step 1b
Author: Claude (Gemini 2.5 Pro via Antigravity)   Executor:
Branch: plan-0008 (created by the founder; AI models run no git)

## Goal
Persist apps and their versioned definitions and expose them through a workspace-scoped REST API, using the validators from PLAN-0007. No frontend, no parties/records/ledger, no AI.

## Out of scope
- Parties, records, ledger, documents, reminders
- AI interview or any customer-facing configuration UI
- Frontend changes
- Git commands of any kind

## Inputs
- AGENTS.md, docs/STATUS.md
- backend/pom.xml (no new dependencies needed — see VERIFY 1)
- backend/src/main/resources/application.properties
- backend/src/main/java/com/followwise/workspace/AccessService.java, Permission.java, WorkspaceController.java
- backend/src/main/java/com/followwise/appdef/ (AppDefinitionValidator, TransitionValidator, TemplateRegistry)
- README sections 6.1, 6.2, 8.3, 8.4, 14.2 and decisions D-41, D-42, D-62–D-66

## VERIFY (current answers with sources)

**V1 — jsonb with Hibernate 7 + Jackson 3.**
`@JdbcTypeCode(SqlTypes.JSON)` on a `String` field mapped to `columnDefinition = "jsonb"` works without any extra dependency. Hibernate 7 auto-detects the Spring Boot 4.1–configured `JsonMapper` (tools.jackson). The definition is stored and retrieved as a raw JSON string — no deserialization by Hibernate, which is correct here (we store the validated JSON verbatim).
Source: https://dev.to — Hibernate 7 JSON type mapping; Spring Boot 4.1 release notes (spring.io).

**V2 — 80 KB body limit returning problem+json 413.**
Spring's multipart limit does not apply to `application/json` bodies. A lightweight `OncePerRequestFilter` that checks `request.getContentLengthLong()` and writes a `ProblemDetail`-shaped JSON response (status 413) is the correct approach. This fires before any controller and avoids reading the stream.
Source: stackoverflow.com — Spring Boot JSON body size; medium.com — problem+json 413.

**V3 — optimistic locking with JdbcClient in the same transaction.**
In an `@Transactional` service method: `UPDATE apps SET current_version=:new WHERE id=:id AND current_version=:expected`; check `rowsAffected`. If 0, throw a custom `StaleVersionException` → 409. This is simpler than Hibernate `@Version` for the case where the client explicitly sends `expectedVersion`.
Source: spring.io — JdbcClient docs; stackoverflow.com — optimistic locking with JdbcClient.

## Files to create or change

| Path | Action | Purpose |
|---|---|---|
| backend/src/main/resources/db/migration/V5__apps.sql | create | apps + app_definition_versions tables |
| backend/src/main/java/com/followwise/app/App.java | create | JPA entity |
| backend/src/main/java/com/followwise/app/AppDefinitionVersion.java | create | JPA entity |
| backend/src/main/java/com/followwise/app/AppRepository.java | create | Spring Data JPA repository |
| backend/src/main/java/com/followwise/app/AppDefinitionVersionRepository.java | create | Spring Data JPA repository |
| backend/src/main/java/com/followwise/app/AppService.java | create | business logic, optimistic locking |
| backend/src/main/java/com/followwise/app/AppController.java | create | REST endpoints |
| backend/src/main/java/com/followwise/app/TemplateController.java | create | GET /api/templates endpoints |
| backend/src/main/java/com/followwise/app/AppExceptionHandler.java | create | 409/413/422 exception → ProblemDetail |
| backend/src/main/java/com/followwise/app/BodySizeFilter.java | create | OncePerRequestFilter 80 KB guard |
| backend/src/main/java/com/followwise/workspace/Permission.java | change | add VIEW_APP, MANAGE_APPS |
| backend/src/main/resources/application.properties | change | add app.max-apps-per-workspace, app.max-definition-versions-per-app |
| .env.example | change | add MAX_APPS_PER_WORKSPACE, MAX_DEFINITION_VERSIONS_PER_APP |
| backend/src/test/java/com/followwise/app/AppApiTest.java | create | integration tests |
| docs/STATUS.md | change | tick Phase 1 step 1b, update Next task |
| README.md section 14.1 | change | append D-67 to D-71 |

## Steps

**Slice 1 — Flyway V5 migration**
Create `V5__apps.sql`:
```sql
CREATE TABLE apps (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
  template_key text,
  current_version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  archived_at timestamptz
);
CREATE INDEX ON apps(workspace_id);

CREATE TABLE app_definition_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  app_id uuid NOT NULL REFERENCES apps(id),
  version int NOT NULL,
  definition jsonb NOT NULL,
  note text,
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (app_id, version)
);
CREATE INDEX ON app_definition_versions(app_id);
```
Check: `mvn test` — Flyway applies V5 without error; existing tests still pass.

**Slice 2 — JPA entities and repositories**
- `App.java`: fields matching the table; `@JdbcTypeCode(SqlTypes.JSON)` is NOT needed here (definition lives in a separate entity).
- `AppDefinitionVersion.java`: `definition` field is `String` with `@Column(columnDefinition = "jsonb") @JdbcTypeCode(SqlTypes.JSON)` — stored and loaded as raw JSON string.
- `AppRepository` and `AppDefinitionVersionRepository` extend `JpaRepository`.
Check: `mvn test` — entities load, schema matches, existing tests pass.

**Slice 3 — Permission additions and BodySizeFilter**
- Add `VIEW_APP(OWNER, STAFF)` and `MANAGE_APPS(OWNER)` to `Permission.java`.
- Add `app.max-apps-per-workspace=${MAX_APPS_PER_WORKSPACE:5}` and `app.max-definition-versions-per-app=${MAX_DEFINITION_VERSIONS_PER_APP:200}` to `application.properties`.
- Add `MAX_APPS_PER_WORKSPACE=5` and `MAX_DEFINITION_VERSIONS_PER_APP=200` to `.env.example`.
- `BodySizeFilter.java`: `OncePerRequestFilter`, applies only to `PUT .../definition` and `POST .../apps` paths; checks `Content-Length` header; if > 81920 bytes writes a `ProblemDetail`-shaped JSON 413 and returns. (Note: does not buffer the stream; purely header-based to avoid memory cost.)
Check: `mvn test` — all pass; filter unit-testable with MockHttpServletRequest.

**Slice 4 — AppService**
Key logic (illustrative, ≤15 lines):
```java
@Transactional
public AppResponse createApp(UUID workspaceId, UUID userId, CreateAppRequest req) {
    accessService.requirePermission(workspaceId, userId, Permission.MANAGE_APPS);
    long count = appRepository.countByWorkspaceIdAndArchivedAtIsNull(workspaceId);
    if (count >= maxAppsPerWorkspace) throw new LimitExceededException("app");
    String definition = templateRegistry.getTemplate(req.templateKey()); // validated at startup
    validationResult = validator.validate(definition);
    // definition is from registry so always valid; validate defensively
    App app = appRepository.save(new App(workspaceId, req.name(), req.templateKey()));
    AppDefinitionVersion v = new AppDefinitionVersion(app.getId(), 1, definition, null, userId);
    appDefinitionVersionRepository.save(v);
    appRepository.updateCurrentVersion(app.getId(), 1); // sets current_version=1, updated_at=now()
    return toResponse(app, definition);
}
```
Save flow for `putDefinition`: validate → transition check → `UPDATE apps SET current_version=:new, name=:name, updated_at=now() WHERE id=:id AND current_version=:expected` → if rowsAffected=0 throw `StaleVersionException` → insert version row. Identical definition (normalized) → 200, no new version.
`archiveApp`: set `archived_at`; subsequent writes return 409 with reason "archived".
Check: `mvn test`.

**Slice 5 — TemplateController and AppController**
Endpoints per D-68:
- `GET /api/templates` — authenticated, returns `[{key, name, description}]`
- `GET /api/templates/{key}` — authenticated, returns full definition JSON
- `POST /api/workspaces/{wid}/apps` body `{templateKey, name}` → 201 `AppResponse`
- `GET /api/workspaces/{wid}/apps` → list (no archived unless `?archived=true`)
- `GET /api/workspaces/{wid}/apps/{aid}` → app + current definition
- `GET /api/workspaces/{wid}/apps/{aid}/versions` → list of version summaries
- `GET /api/workspaces/{wid}/apps/{aid}/versions/{n}` → version with definition
- `PUT /api/workspaces/{wid}/apps/{aid}/definition` body `{expectedVersion, definition, note?}` → 200 or 409/422
- `POST /api/workspaces/{wid}/apps/{aid}/archive` → 200
Check: `mvn test`.

**Slice 6 — AppExceptionHandler**
Map to `ProblemDetail` (RFC 9457, no stack traces):
- `ValidationException` (from `AppDefinitionValidator`) → 422, errors list (≤20)
- `StaleVersionException` → 409 "stale_version"
- `LimitExceededException` → 409 "limit_exceeded"
- App archived on write → 409 "app_archived"
- Version limit exceeded → 409 "version_limit"
- Template not found → 404
Check: `mvn test`.

**Slice 7 — Integration tests** (`AppApiTest.java`, Testcontainers)
Tests (all required per D-68/D-69/D-70):
- Create app from each template → 201, name matches definition
- List, GET by id, versions list, version by number return correct data
- Permission matrix on every endpoint: OWNER all, STAFF VIEW_APP only, non-member 404, portal user 404
- App id from workspace B queried under workspace A → 404
- Valid PUT definition creates version 2; `apps.name` follows definition name
- Stale `expectedVersion` → 409 "stale_version"
- Invalid definition → 422, ≤20 errors, version count unchanged
- Transition violation → 422
- Identical definition → 200, no new version row
- 6th app in workspace → 409 "limit_exceeded"
- 201st version → 409 "version_limit"
- Body > 80 KB → 413
- Archived app write → 409 "app_archived"
- Two concurrent saves with same `expectedVersion` → exactly one 200, one 409 (use two transactions in test)
- Create app is atomic (simulate version insert failure → no orphan app row)
- Stored definition round-trips byte-for-byte
- Error responses contain no stack traces or echoed definition content
- V5 migration applies on top of V4 schema (verified by Testcontainers starting fresh)
Check: `mvn test` — all tests green, record new+existing counts in report.

**Slice 8 — Docs**
- Append D-67 to D-71 to README.md section 14.1 (exact wording in Decisions block below).
- Update `docs/STATUS.md`: tick Phase 1 step 1b, set Next task = PLAN-0009.
Check: README and STATUS updated correctly; `mvn test` still green.

## Checks (how to prove it works)
- `cd backend && mvn test` green after each slice.
- After slice 7: test report shows ≥20 new test methods and all pass.
- Manual smoke (optional, not executor's task): POST create-app, GET versions, PUT definition, verify 409 on stale.

## Acceptance criteria
- [ ] V5 migration applies cleanly on top of V4.
- [ ] All 9 endpoints respond correctly.
- [ ] Permission matrix enforced: OWNER all, STAFF read-only, non-members 404.
- [ ] App and version limits enforced.
- [ ] 80 KB body guard returns 413.
- [ ] Stale version returns 409.
- [ ] Invalid / transitioning definition returns 422 with ≤20 errors, no stack trace.
- [ ] All tests green.

## Risks and assumptions
- Assumption: `AppDefinitionValidator.validate(String)` returns a result with `isValid()` and `errors()` (confirmed from PLAN-0007).
- Assumption: `TransitionValidator.validateTransition(String, String)` signature confirmed from PLAN-0007.
- Assumption: identical-definition check uses normalized JSON comparison (sort keys); if the validator's mapper is used, this is consistent.
- Risk: Hibernate 7 `@JdbcTypeCode(SqlTypes.JSON)` on a `String` field may need `@Column(columnDefinition="jsonb")` explicitly — executor must verify at compile time.
- Risk: concurrent-save test requires two database connections; Testcontainers pool must allow it. Use `@Transactional(propagation = REQUIRES_NEW)` in a helper service or raw JDBC.

## Decisions needed
(none — D-67 to D-71 already approved by orchestrator)

## Decisions block (executor appends to README 14.1 verbatim in slice 8)

| ID | Decision | Rationale / consequence |
|---|---|---|
| D-67 | Persistence (new Flyway migration after V4): apps(id uuid PK, workspace_id FK, name, template_key null, current_version int, created_at, updated_at, archived_at null) and app_definition_versions(id uuid PK, app_id FK, version int, definition jsonb, note text null, created_by FK users, created_at, unique(app_id, version)). Version rows are immutable and never updated or deleted in v1. Apps are archived, never hard-deleted. Every query scopes by workspace_id and app_id together: an app id that belongs to another workspace is a 404. | Immutable version history and strict tenant isolation. |
| D-68 | Endpoints: GET /api/templates and GET /api/templates/{key} (authenticated users); POST /api/workspaces/{workspaceId}/apps {templateKey, name}; GET /api/workspaces/{workspaceId}/apps; GET .../apps/{appId} (app plus current definition); GET .../apps/{appId}/versions and .../versions/{n}; PUT .../apps/{appId}/definition {expectedVersion, definition, note optional}; POST .../apps/{appId}/archive. MANAGE_APPS (OWNER) for create/archive/write; VIEW_APP (OWNER+STAFF) for reads. Non-members get 404. | Minimal surface area for Phase 1 step 1b. |
| D-69 | Save flow: validate (structural+semantic) → transition check → UPDATE apps WHERE current_version=expectedVersion (rowsAffected=0 → 409 stale_version) → insert version row. Identical normalized definition → 200, no new row. Operation is atomic in one @Transactional method. | Optimistic locking without a separate locking table. |
| D-70 | Abuse limits: MAX_APPS_PER_WORKSPACE=5 (env, default 5), MAX_DEFINITION_VERSIONS_PER_APP=200 (env, default 200). Body limit 80 KB on definition endpoints enforced by a servlet filter checking Content-Length, returning 413 problem+json before reading the stream. | Bounded resource usage on the free tier. |
| D-71 | apps.name is initialized from the definition's top-level "name" field on create, and kept in sync on every accepted PUT definition. The API never accepts a separate name field for update (the definition is the single source of truth). | Avoids name drift between the app row and its definition. |

## STATUS.md update (executor does this)
- Task log line: `PLAN-0008 Apps and definition versions API — DONE YYYY-MM-DD`
- Tick: `[x] 1b. Apps and definition versions REST API (PLAN-0008)`
- Next task: PLAN-0009

---
## Orchestrator amendments (binding, override anything above that conflicts)
A. Ignore any git instruction. Run no git command.
B. Persistence: use JdbcClient with explicit SQL for apps and app_definition_versions. NO JPA entities, no Hibernate JSON mapping (drop the entity parts of Slice 2; use records for rows and one AppStore class). Write jsonb with CAST(:definition AS jsonb), read with definition::text. V5 also adds CHECK (current_version >= 0) and CHECK (version >= 1). jsonb normalizes whitespace and key order, so the round-trip test compares JSON trees (JsonNode equality), not bytes; the identical-definition check also uses JsonNode equality.
C. Create flow: TemplateRegistry.getTemplate returns Optional<JsonNode>; take a deep copy, set its "name" (request name, 1 to 100 chars, label rules) and "timezone" (optional, IANA) in the copy, run the full AppDefinitionValidator, store it as version 1, and set current_version = 1, all in one transaction. First run SELECT id FROM workspaces WHERE id = :wid FOR UPDATE, then count. Limit: active (unarchived) apps < MAX_APPS_PER_WORKSPACE, and total apps including archived < 5 x MAX_APPS_PER_WORKSPACE; either failure is 409 limit_exceeded. Create body is {templateKey, name, timezone?}.
D. Save order: permission and scoping, then expectedVersion must be an integer >= 1, then archived app returns 409 app_archived, then expectedVersion != current_version returns 409 stale_version (include currentVersion), then definition equal to the current one returns 200 with nothing written, then validate, then transition check (422 on failure), then version limit (409 version_limit), then conditional UPDATE apps SET current_version=:new, name=:name_from_definition, updated_at=now() WHERE id=:aid AND workspace_id=:wid AND current_version=:expected AND archived_at IS NULL (0 rows means 409 stale_version), then insert the version row. note is at most 500 characters with no control characters.
E. Request parsing: the create and PUT definition endpoints receive the raw body as bytes and parse it with the strict parser (STRICT_DUPLICATE_DETECTION, max nesting depth, 80 KB size, unknown envelope keys rejected). Never bind the definition through Spring's default message conversion. Pass the parsed definition subtree to the validator. A duplicate key inside definition is rejected with a fixed-text problem+json (400 or 422) and a test proves it. Other request DTOs also reject unknown fields.
F. BodySizeFilter: applies to POST .../apps and PUT .../definition. Check Content-Length when present, and ALWAYS wrap the input stream in a counting wrapper that aborts at 81920 bytes (covers chunked requests). Both produce 413 problem+json with fixed text.
G. Permissions (D-42): non-member 404; member without the permission 403. VIEW_APP = OWNER, STAFF; MANAGE_APPS = OWNER. PORTAL_USER gets 403 on every app endpoint; STAFF gets 403 on create, PUT definition, and archive and 200 on the read endpoints. Every app query scopes workspace_id and app_id together, including the versions endpoints; an app of workspace B under workspace A's path is 404. Fix slice 7 and the acceptance criteria accordingly.
H. Templates: GET /api/templates returns [{key, name}] (no description). The key path variable must match ^[a-z0-9-]+$; unknown key is 404.
I. Error bodies are problem+json; 422 has errors[] of at most 20 {path, message}; 409 uses type values stale_version, limit_exceeded, version_limit, app_archived. No stack traces and no echoed input.
J. Extra tests: duplicate key inside definition; oversized body without Content-Length (chunked) gives 413; STAFF gets 403 on write endpoints; PORTAL_USER gets 403 everywhere; two parallel creates when 4 apps exist give exactly one success; invalid timezone on create is rejected; unknown envelope key rejected; note over 500 characters rejected; unknown template key is 404; name in the request is applied to the stored definition; archived app slot behavior and total cap.
K. STATUS log line format: | YYYY-MM-DD | PLAN-0008: apps and definition versions API (Phase 1, step 1b) | <model> | Done |
L. Decision wording. Append these instead of the plan's block (D-67 as written in the plan is fine):
D-68 Endpoints: GET /api/templates and GET /api/templates/{key} (authenticated); POST /api/workspaces/{workspaceId}/apps {templateKey, name, timezone optional}; GET .../apps; GET .../apps/{appId} (app plus current definition); GET .../apps/{appId}/versions and .../versions/{n}; PUT .../apps/{appId}/definition {expectedVersion, definition, note optional}; POST .../apps/{appId}/archive. New permissions VIEW_APP (OWNER, STAFF) and MANAGE_APPS (OWNER); a member without the permission gets 403 and a non-member 404 (D-42). Creating from a template copies it, applies the requested name and timezone, validates, and stores version 1 in one transaction.
D-69 Saving a definition: expectedVersion check, identical check, structural and semantic validation, transition check, version limit, then one conditional UPDATE on the app row (rows = 0 means 409 stale_version) and the version insert in the same transaction. Validation failure is 422 with at most 20 errors and nothing saved. An identical definition returns 200 and creates nothing. Archived apps cannot be edited (409).
D-70 Limits by env var: MAX_APPS_PER_WORKSPACE (default 5, active apps; total including archived is capped at 5 times that) and MAX_DEFINITION_VERSIONS_PER_APP (default 200) return 409. Definition request bodies are limited to 80 KB by a filter that checks Content-Length and also counts the bytes actually read, returning 413 problem+json.
D-71 apps.name is 1 to 100 characters with the label rules and always equals the name inside the current definition. The API has no separate rename; renaming is a definition change.
D-72 App tables are accessed with JdbcClient and explicit SQL (no JPA entities). jsonb is written with CAST and read as text; the stored definition is compared and returned as a JSON tree.
D-73 Endpoints that accept a definition read the raw body with the strict parser (duplicate keys, unknown keys, depth, and size limits) and never rely on Spring's default JSON binding for the definition.
---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks:
