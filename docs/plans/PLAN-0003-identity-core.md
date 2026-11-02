# PLAN-0003: Identity core

Status: DONE        <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 0, step 3a
Author: Claude Sonnet 4.5   Executor: Gemini 3.8 Flash
Branch: plan-0003

## Goal
Add users, workspaces, memberships with roles, registration, login, JWT access + opaque refresh tokens, one central permission check, and tenant-isolation tests to `backend/`. No email sending.

## Out of scope
Email verification/reset (PLAN-0004), parties/records/ledger, audit log table, IP rate limiting, staff invitation endpoints, frontend, deployment.

## Inputs
- `AGENTS.md` §4–5; `docs/STATUS.md`; `.env.example`
- `backend/pom.xml`; `backend/src/main/resources/application.properties`
- README §8.3, 8.4, 9, 11, 14.1 (decision log), 14.2

## New env vars (executor appends to `.env.example`, names only, no inline comments)
```
AUTH_COOKIE_SECURE=
```
`JWT_SECRET`, `JWT_ACCESS_TTL_MINUTES`, `JWT_REFRESH_TTL_DAYS` already present.

## New dependencies
All Spring Boot starters and `spring-security-test` are BOM-managed — no explicit version needed.
`org.testcontainers` is **not** BOM-managed; pin to **1.21.4** (Maven Central, mvnrepository.com/artifact/org.testcontainers/testcontainers/1.21.4).

| Artifact | Version | Scope |
|---|---|---|
| `spring-boot-starter-security` | BOM-managed (Spring Security 7.x) | compile |
| `spring-boot-starter-oauth2-resource-server` | BOM-managed | compile |
| `spring-security-test` | BOM-managed | test |
| `spring-boot-testcontainers` | BOM-managed (matches Boot 4.1.x) | test |
| `org.testcontainers:testcontainers` | **1.21.4** | test |
| `org.testcontainers:postgresql` | **1.21.4** | test |

Source: spring.io BOM for Spring starters; mvnrepository.com for Testcontainers 1.21.4 (2025).

## CSRF note
Enable `CookieCsrfTokenRepository.withHttpOnlyFalse()` for `POST /api/auth/refresh` since it sends credentials via an HttpOnly cookie — CSRF protection is recommended.

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| `backend/pom.xml` | edit | add security, oauth2-resource-server, spring-security-test, testcontainers deps |
| `backend/src/main/resources/application.properties` | edit | JWT config keys, security config, fail-fast JWT_SECRET check |
| `.env.example` | edit | add `AUTH_COOKIE_SECURE=` |
| `backend/src/main/resources/db/migration/V2__identity.sql` | create | tables: users, workspaces, memberships |
| `backend/src/main/java/com/followwise/identity/` | create | User entity, UserRepository, AuthController, AuthService, PasswordService, TokenService, RefreshTokenRepository, LockoutService |
| `backend/src/main/java/com/followwise/workspace/` | create | Workspace entity, WorkspaceRepository, WorkspaceController, WorkspaceService, AccessService (central permission check), Membership entity/repo |
| `backend/src/main/java/com/followwise/security/` | create | SecurityConfig, JwtProperties, JwtDecoder bean, CookieRefreshFilter |
| `README.md` | edit | append D-41 to D-45 to decision log (§14.1) |
| `docs/STATUS.md` | edit | task log line, tick step 3a, update next task |

## Steps

**Slice 1 — deps** (commit: `feat(identity): add security and testcontainers deps`)
1. Add the six dependencies from the table above to `backend/pom.xml`. No version tags for Boot-managed artifacts; pin testcontainers to 1.21.4.
2. Check: `mvn compile -f backend/pom.xml` exits 0.

**Slice 2 — V2 migration** (commit: `feat(identity): V2 Flyway migration — users workspaces memberships`)
1. Create `V2__identity.sql` with tables `users` (uuid PK, email lowercase unique, password_hash, failed_attempts int default 0, locked_until timestamptz, created_at/updated_at timestamptz), `workspaces` (uuid PK, name, owner_id FK users, created_at), `memberships` (uuid PK, user_id FK users, workspace_id FK workspaces, role varchar CHECK IN ('OWNER','STAFF','PORTAL_USER'), unique(user_id,workspace_id)).
2. Check: `mvn test -pl backend -Dtest=SmokeTest` starts app, Flyway applies V2, no error (uses Testcontainers).

**Slice 3 — entities + repositories** (commit: `feat(identity): JPA entities and repositories`)
1. Add `@Entity` classes `User`, `Workspace`, `Membership` in their respective packages. UUID PKs. All timestamps UTC. Enums `Role`.
2. Add Spring Data `JpaRepository` interfaces.
3. Check: `mvn compile -f backend/pom.xml` exits 0.

**Slice 4 — registration, login, lockout** (commit: `feat(identity): register, login, lockout`)
1. Fail-fast in `JwtProperties` `@PostConstruct`: if `JWT_SECRET` is blank or shorter than 32 bytes, throw `IllegalStateException` (skip in test profile via `@ConditionalOnProperty` or `@Profile("!test")`).
2. Implement `POST /api/auth/register` (create user + first workspace with role OWNER; email normalized lowercase; password hashed with `DelegatingPasswordEncoder` bcrypt default; length 10–128 validated).
3. Implement `POST /api/auth/login` (verify password; increment `failed_attempts` on failure; lock for 15 min after 5 failures; generic "Invalid credentials" error on any failure).
4. Check tests: `PasswordEncodingTest` verifies encoding+matching; `AuthIntegrationTest` covers: happy register+login, wrong-password generic error, lockout at attempt 6.

**Slice 5 — JWT access token + resource server** (commit: `feat(identity): JWT access token and resource server config`)
1. Configure `SecurityConfig`: `http.oauth2ResourceServer(jwt -> jwt.decoder(...))`. Permit `/api/auth/**` and `/health`. Require auth for all else.
2. `TokenService.issueAccessToken()`: HS256 JWT signed with `JWT_SECRET`, expiry `JWT_ACCESS_TTL_MINUTES`, subject = userId, claim `workspaceId`.
3. Return access token in login/register response body.
4. Check tests: expired/invalid token → 401; unauthenticated request → 401.

**Slice 6 — refresh token** (commit: `feat(identity): opaque refresh token, rotation, family revocation`)
1. On login/register: generate a random opaque token; store `SHA-256(token)` in `refresh_tokens` table (uuid PK, user_id, token_hash, family_id, used boolean, expires_at). Set HttpOnly cookie (`Secure` = `AUTH_COOKIE_SECURE`, `SameSite=Strict`, path `/api/auth/refresh`).
2. Add `V2b__refresh_tokens.sql` (or fold into V2 if not yet migrated). Flyway migration number: `V3__refresh_tokens.sql` to keep V2 atomic.
3. `POST /api/auth/refresh`: validate cookie token hash, rotate (mark old used, issue new), return new access token. If hash not found or used → revoke whole family (delete all family rows), return 401.
4. `POST /api/auth/logout`: delete refresh token row; clear cookie.
5. Enable CSRF for `POST /api/auth/refresh` with `CookieCsrfTokenRepository.withHttpOnlyFalse()`.
6. Check tests: refresh rotation; reuse of old token revokes family and returns 401.

**Slice 7 — workspace endpoints + central access service** (commit: `feat(identity): workspace endpoints, central access check, tenant isolation`)
1. `AccessService.requireMember(workspaceId, userId)`: if no membership → throw `WorkspaceNotFoundException` (→ 404). `requirePermission(workspaceId, userId, permission)`: if no role-grants-permission → throw `ForbiddenException` (→ 403). Role-to-permission map in `Permission` enum.
2. Implement: `GET /api/workspaces` (list caller's workspaces); `GET /api/workspaces/{id}` (requireMember); `GET /api/workspaces/{id}/members` (requirePermission VIEW_MEMBERS).
3. Errors use `ProblemDetail` (RFC 9457).
4. Check tests: user A requests workspace B endpoint → 404; STAFF without VIEW_MEMBERS permission → 403; OWNER → 200.

**Slice 8 — me endpoint, docs, status** (commit: `docs: append D-41–D-45, update STATUS and env`)
1. `GET /api/me`: return caller's id, email, list of workspaces with role.
2. Add `AUTH_COOKIE_SECURE=` to `.env.example`.
3. Append D-41 to D-45 to README §14.1 decision log (exact wording from this plan's header block — see Orchestrator amendments section when present).
4. Update `docs/STATUS.md`: add task log line, split step 3 into "3a identity core (PLAN-0003)" and "3b email verification + adapter (PLAN-0004)", tick 3a, set next task to PLAN-0004.

## Checks
1. `mvn test -pl backend` exits 0 (all slices integrated).
2. `curl http://localhost:8080/health` → 200 with no DB call.
3. `curl http://localhost:8080/v3/api-docs` → 200 JSON.
4. `docker build -t followwise-backend backend/` exits 0.
5. Password encoding and matching verified by unit test.
6. Register + login happy path integration test passes.
7. Wrong password → response body contains generic message, HTTP 401.
8. Lockout: 6th failed login attempt within window → 403 or 423 (not 401).
9. Refresh rotation: new token issued, old token invalidated.
10. Refresh reuse detection: second use of old token → 401 and all family tokens deleted.
11. Expired or malformed JWT → 401.
12. Unauthenticated request to protected endpoint → 401.
13. User A's valid token on `GET /api/workspaces/{workspaceB-id}` → 404.
14. STAFF token on permission-restricted endpoint → 403.
15. OWNER token on same endpoint → 200.
16. App starts with `PORT=9090` and answers on 9090 (from PLAN-0002 check).

## Acceptance criteria
- [x] All 16 checks pass with no test skipped.
- [x] `JWT_SECRET` absent or < 32 bytes: app refuses to start (no test-profile bypass).
- [x] No secret, credential, or personal data in logs.
- [x] No new env var committed with a real value.
- [x] `mvn test` needs only Docker running (Testcontainers, no local Postgres required).

## Risks and assumptions
- Spring Security 7 with Boot 4.1 ships `CookieCsrfTokenRepository` — verify during execution.
- `DelegatingPasswordEncoder` prefix `{bcrypt}` stored in DB; executor must not strip it.
- Testcontainers 1.21.4 requires Docker Desktop or Docker Engine on the CI host.
- JWT HS256 with a ≥ 256-bit secret is sufficient for a self-signed token (no JWKS endpoint needed).
- `spring-boot-starter-oauth2-resource-server` includes Nimbus JOSE + JWT; no extra JWT library needed.
- Flyway migration names V2 and V3 are assumed not already taken by executor — verify against `db/migration/`.

## Orchestrator amendments (binding, override anything above that conflicts)
A. Dependencies and commands
1. Remove the Testcontainers 1.21.4 pins. Use Testcontainers 2.x: import testcontainers-bom (latest 2.0.x), and use testcontainers-postgresql and testcontainers-junit-jupiter (test scope) plus spring-boot-testcontainers. Verify with `mvn dependency:tree` that only one Testcontainers major version is present; record exact versions. If MockMvc is used, add spring-boot-starter-webmvc-test. Jackson 3 packages are tools.jackson.*.
2. Replace every `-pl backend` with `cd backend && mvn ...`.
B. Test infrastructure
1. Convert FollowWiseApplicationTests and all new tests to Testcontainers (Postgres 16, one shared container per run, @ServiceConnection). `mvn test` must not need the compose database. Test properties supply a valid 32+ byte JWT_SECRET.
2. The fail-fast JWT_SECRET check has NO test-profile bypass. Add a test that startup fails for a missing secret and for a 31-byte secret.
C. Schema
1. Drop workspaces.owner_id. The owner is the membership with role OWNER.
2. Migrations: V2__identity.sql (users, workspaces, memberships) and V3__refresh_tokens.sql: refresh_tokens(id uuid PK, user_id FK, family_id uuid, token_hash char(64) UNIQUE, created_at, expires_at, used_at null, revoked_at null) with an index on family_id. Add V3 to the files table. Ignore the "V2b" text.
3. Revocation sets revoked_at on all rows of the family; never delete them. Check 10 reads "family revoked", not "deleted".
D. Tokens and cookies
1. Access JWT claims: sub (userId), iat, exp, iss="followwise" (validated). NO workspaceId claim. Authorization always comes from memberships in the database.
2. JWT_SECRET must be at least 32 UTF-8 bytes.
3. Refresh cookie: HttpOnly, SameSite=Strict, Path=/api/auth (not /api/auth/refresh), Max-Age = refresh TTL, Secure from AUTH_COOKIE_SECURE (default true when unset).
4. CSRF: disable Spring CSRF. For POST /api/auth/refresh and /api/auth/logout require header X-FollowWise-Client: web, and if an Origin header is present it must equal APP_BASE_URL; otherwise 403 problem+json. Do not enable CORS (design D-30).
5. Refresh rotation is one atomic statement: UPDATE refresh_tokens SET used_at=now() WHERE token_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>now(); exactly one row updated means success. Otherwise treat as invalid or reuse: if the hash exists, revoke its whole family; return 401. Logout revokes the whole family and clears the cookie. Record in the report: two simultaneous refreshes will trigger reuse detection (the frontend must serialize refresh).
E. Login and registration
1. Every login failure returns the identical 401 problem body: unknown email, wrong password, locked account, and a correct password during lockout. Replace check 8: the 6th attempt returns the same 401, and the correct password during lockout also returns that 401.
2. For an unknown email still run a dummy bcrypt match so timing is similar.
3. failed_attempts is incremented with one atomic UPDATE and reset to 0 on success.
4. Password length is 10 to 72 UTF-8 bytes (bcrypt reads only 72). Tests at 9, 10, 72, and 73 bytes.
5. Registering an existing email returns 409. Known enumeration trade-off until PLAN-0004 adds verification; record it in the report.
6. Registration is one @Transactional operation (user, workspace, OWNER membership). Workspace name 1 to 100 characters, default "My workspace". Add a rollback test.
F. Authorization
1. Permission map: VIEW_WORKSPACE = OWNER, STAFF. VIEW_MEMBERS = OWNER only. PORTAL_USER has neither (403 on both). Non-members get 404 on both.
2. GET /api/workspaces/{id} requires VIEW_WORKSPACE; GET /api/workspaces/{id}/members requires VIEW_MEMBERS. Test all three roles on both endpoints plus a non-member.
3. Tests create STAFF and PORTAL_USER memberships through repositories (no invite endpoint exists).
G. HTTP layer
1. Permit exactly: POST /api/auth/register, /login, /refresh, /logout and GET /health. In the local profile only, also permit /v3/api-docs/** and /swagger-ui/**. Check 3 applies to the local profile only.
2. Configure the authentication entry point and access-denied handler to return RFC 9457 problem+json. Test the 401 content type is application/problem+json.
3. Never log passwords, tokens, or the refresh cookie. Add a log-capture test for a failed login.
H. Extra checks: cookie flags on Set-Cookie (HttpOnly; SameSite=Strict; Path=/api/auth); logout revokes the family (refresh afterward gives 401); refresh without X-FollowWise-Client gives 403; JWT with wrong issuer gives 401.
I. Decision log wording. Append D-41, D-42, D-45 exactly as in the plan. Use these instead of the plan's D-43 and D-44, and add D-46:
D-43 Tokens: access JWT (HS256, JWT_SECRET of at least 32 bytes, 15 min, claims sub/iat/exp/iss only, no workspace claim) validated by Spring Security resource server; authorization is always read from memberships. Refresh token is a random opaque value stored only as a SHA-256 hash, rotated atomically on every use; reuse revokes its whole family (rows kept with revoked_at). It travels in an HttpOnly, SameSite=Strict cookie with Path=/api/auth, Secure from AUTH_COOKIE_SECURE.
D-44 Passwords: DelegatingPasswordEncoder (bcrypt default), length 10 to 72 UTF-8 bytes, no composition rules. Every login failure, including a locked account, returns the same 401; unknown emails still run a dummy hash. Lockout after 5 failed attempts for 15 minutes using atomic database counters.
D-46 CSRF: Spring CSRF is disabled (stateless bearer API). Refresh and logout require the X-FollowWise-Client header and a matching Origin when present; no CORS is enabled because the browser only talks to the Next.js origin.

## Decisions needed
None. D-41 to D-45 are pre-approved. Executor appends them verbatim to README §14.1.

**D-41** Identity model: tables `users`, `workspaces`, `memberships` (user, workspace, role OWNER|STAFF|PORTAL_USER). A user can belong to many workspaces. Registration creates an Owner user plus a first workspace. Portal users are invited later, never self-registered. UUID primary keys, timestamptz, emails normalized to lowercase with a unique index (no citext).

**D-42** Workspace context is explicit in the URL (`/api/workspaces/{workspaceId}/...`) and checked by one central access service. Non-members get 404 (no existence leak). Members lacking a permission get 403. The role-to-permission map lives in code.

**D-43** Tokens: access JWT (HS256 signed with `JWT_SECRET`, 15 min) validated by Spring Security resource server. Refresh token is a random opaque value stored only as a SHA-256 hash, rotated on every use; reuse of an old token revokes its whole family. The refresh token travels in an HttpOnly cookie set by the backend (`Secure` flag from `AUTH_COOKIE_SECURE`).

**D-44** Passwords: `DelegatingPasswordEncoder` (bcrypt default), length 10 to 128, no composition rules. Login errors are generic. Lockout after 5 failed attempts for 15 minutes using database counters.

**D-45** Tests use Testcontainers Postgres 16 so `mvn test` needs only Docker running. Errors use RFC 9457 problem details (`ProblemDetail`).

## STATUS.md update (executor does this)
- Task log line: `| YYYY-MM-DD | PLAN-0003: identity core (users, workspaces, memberships, auth, tokens, tenant-isolation tests) | <model> | Done |`
- Split Phase 0 step 3 into: `- [x] 3a. Identity core (PLAN-0003)` and `- [ ] 3b. Email verification, password reset, email adapter (PLAN-0004)`
- Tick 3a.
- Set "Next task" to `PLAN-0004: email verification, password reset, email adapter (Phase 0, step 3b)`.

---
## Execution report (executor fills)
- What was done:
  - Added Spring Security 7, OAuth2 Resource Server, and Testcontainers 2.0.5 with Postgres 16 support to `backend/pom.xml`.
  - Converted the test suite to a shared Testcontainers PostgreSQL 16 container via `@ServiceConnection`.
  - Created Flyway migrations `V2__identity.sql` (users, workspaces, memberships) and `V3__refresh_tokens.sql` (refresh_tokens).
  - Implemented domain entities and repositories with atomic database counters for lockout and atomic single-statement rotation for refresh tokens.
  - Implemented `PasswordService` using `DelegatingPasswordEncoder` (bcrypt default) with 10–72 UTF-8 bytes length validation and dummy bcrypt matching on unknown email.
  - Implemented `AuthService` and `AuthController`: registration (transactional user, workspace, OWNER membership), login (generic 401 on wrong password or locked account), refresh token rotation and family revocation on reuse, logout.
  - Configured `SecurityConfig`: OAuth2 resource server with Nimbus HS256 `JwtDecoder` verifying issuer "followwise", RFC 9457 `ProblemDetail` error responses for 401/403, and `ClientOriginCheckFilter` requiring `X-FollowWise-Client: web` and matching Origin on refresh/logout.
  - Implemented workspace endpoints (`GET /api/workspaces`, `/api/workspaces/{id}`, `/api/workspaces/{id}/members`) with central `AccessService` enforcing zero existence leakage (404 for non-members) and role-based permissions (403 for unauthorized members).
  - Implemented user profile endpoint (`GET /api/me`).
  - Appended decisions D-41 through D-46 to `README.md` §14.1 decision log.
  - Added `AUTH_COOKIE_SECURE=` to `.env.example`.
  - Updated `docs/STATUS.md` ticking step 3a and setting next task to PLAN-0004.
- Exact dependency versions used:
  - `org.springframework.boot:spring-boot-starter-security:4.1.1` (pulls Spring Security `7.1.1`)
  - `org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.1.1` (pulls Nimbus JOSE/JWT `10.9.1`)
  - `org.springframework.security:spring-security-test:7.1.1`
  - `org.springframework.boot:spring-boot-starter-webmvc-test:4.1.1`
  - `org.springframework.boot:spring-boot-testcontainers:4.1.1`
  - `org.testcontainers:testcontainers-bom:2.0.5`
  - `org.testcontainers:testcontainers:2.0.5`
  - `org.testcontainers:testcontainers-postgresql:2.0.5`
  - `org.testcontainers:testcontainers-junit-jupiter:2.0.5`
- Commands run and results:
  - `cd backend && mvn dependency:tree`: verified single Testcontainers 2.0.5 major version.
  - `cd backend && mvn test`: all 26 tests passed in 48s.
  - `docker build -t followwise-backend backend/`: image built successfully.
  - Docker container running with `PORT=9090` on default profile: `GET /health` returned 200, `GET /v3/api-docs` returned 401.
  - Docker container running with `SPRING_PROFILES_ACTIVE=local` on `PORT=9090`: `GET /v3/api-docs` returned 200, `GET /health` returned 200.
- Deviations from the plan:
  - In Testcontainers 2.0, `PostgreSQLContainer` is non-generic (use `PostgreSQLContainer`, not `PostgreSQLContainer<?>`).
  - In Spring Boot 4.1, `@AutoConfigureMockMvc` is located in `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` and requires `spring-boot-starter-webmvc-test`.
  - Transaction management on login: `AuthService.login` uses `noRollbackFor = InvalidCredentialsException.class` so that atomic failed attempt counters and lockout state are committed to Postgres on 401 responses.
- Follow-ups or new risks:
  - Per amendment E.5: Duplicate email returns 409 (enumeration trade-off until PLAN-0004 adds verification).
  - Per amendment D.5: Two simultaneous refresh requests with the same cookie will trigger reuse detection; the frontend must serialize refresh calls.
