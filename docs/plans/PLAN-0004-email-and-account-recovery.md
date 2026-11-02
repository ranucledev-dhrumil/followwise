# PLAN-0004: Email sending layer, email verification, and password reset

Status: APPROVED
Phase / step: Phase 0, step 3b
Author: Claude Sonnet 4.5   Executor: (model that executed)
Branch: plan-0004

## Goal
Add an outbox-based email layer, email verification, and password reset to backend/. Completes Phase 0 step 3.

## Out of scope
- Auth, entities, ledger, frontend, staff invitation, IP rate limiting, audit-log table.
- Any file outside backend/ and docs/.

## Inputs
- README sections 8.5, 9, 11, 14.2; decision log D-19, D-26, D-41–D-46
- backend/pom.xml, backend/src/main/resources/*.properties
- .env.example

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| backend/pom.xml | edit | add spring-boot-starter-mail (BOM-managed, no version) |
| .env.example | edit | add MAIL_TRANSPORT= |
| backend/src/main/resources/db/migration/V4__email_outbox_auth_tokens.sql | create | email_outbox, auth_tokens, users.email_verified_at |
| backend/src/main/java/com/followwise/email/ | create (package) | EmailSender interface, SmtpEmailSender, LogEmailSender, MailConfig |
| backend/src/main/java/com/followwise/outbox/ | create (package) | EmailOutbox entity+repo, OutboxScheduler |
| backend/src/main/java/com/followwise/authtoken/ | create (package) | AuthToken entity+repo, AuthTokenService |
| backend/src/main/java/com/followwise/identity/ | edit | AuthService (enqueue verify email on register), AuthController (new endpoints), new request/response records |
| backend/src/test/java/com/followwise/ | create | EmailVerificationIntegrationTest, PasswordResetIntegrationTest |
| README.md | edit | append D-47–D-52 to decision log |
| docs/STATUS.md | edit | tick 3b, update Next task |

## Steps

### Slice 1 — pom.xml and env  (commit: `feat(email): add mail starter and MAIL_TRANSPORT env var`)
1. In backend/pom.xml add under `<dependencies>`:
   `spring-boot-starter-mail` (groupId `org.springframework.boot`, no `<version>` — BOM-managed by Boot 4.1).
2. In .env.example add after MAIL_DAILY_CAP: `MAIL_TRANSPORT=`
3. Check: `cd backend && mvn dependency:resolve -q` exits 0; `grep mail .env.example` shows the line.

### Slice 2 — Flyway V4 migration  (commit: `feat(email): V4 migration — email_outbox auth_tokens email_verified_at`)
1. Create `V4__email_outbox_auth_tokens.sql` with:
   - `ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;`
   - Table `email_outbox`: id UUID PK, kind TEXT NOT NULL, to_address TEXT NOT NULL, subject TEXT NOT NULL, body_text TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'QUEUED', attempts INT NOT NULL DEFAULT 0, last_error TEXT, send_after TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). Index on (status, send_after) WHERE status='QUEUED'.
   - Table `auth_tokens`: id UUID PK, user_id UUID NOT NULL REFERENCES users(id), type TEXT NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). Index on (user_id, type) WHERE used_at IS NULL.
2. Check: `cd backend && mvn test -pl . -Dtest=SmokeTest` exits 0 (Flyway runs V4).

### Slice 3 — EmailSender interface and implementations  (commit: `feat(email): EmailSender interface, SMTP and log transports, startup validation`)
1. Create `com.followwise.email.EmailSender` interface: `void send(String to, String subject, String bodyText)`.
2. Create `SmtpEmailSender` (activated when `MAIL_TRANSPORT=smtp` or missing): uses `JavaMailSender`, sets From (MAIL_FROM_ADDRESS, MAIL_FROM_NAME) and Reply-To (MAIL_REPLY_TO), sends plain-text `SimpleMailMessage`. Fails to start if MAIL_HOST, MAIL_USERNAME, or MAIL_PASSWORD is blank.
3. Create `LogEmailSender` (activated when `MAIL_TRANSPORT=log`): logs full email to SLF4J INFO. Fails to start (IllegalStateException) if active Spring profile is `prod`.
4. Create `MailConfig` @Configuration: reads `MAIL_TRANSPORT`, `spring.profiles.active`, selects implementation; startup validation runs in `@PostConstruct`.
5. Check: `cd backend && mvn test` exits 0; add `EmailSenderConfigTest` with `@SpringBootTest` (local profile, MAIL_TRANSPORT=log) verifying context loads and `send()` logs without throwing.

### Slice 4 — Outbox entity, repository, scheduler  (commit: `feat(email): outbox entity, repo, scheduler worker`)
1. Create `com.followwise.outbox.EmailOutbox` @Entity mapping `email_outbox`.
2. Create `OutboxRepository` extends `JpaRepository`; add `@Lock(PESSIMISTIC_WRITE)` + `@Query` to claim up to N QUEUED rows with `SKIP LOCKED`, ordered by kind='VERIFY_EMAIL' first, then created_at.
3. Create `OutboxScheduler` @Component annotated `@ConditionalOnProperty(name="app.scheduler.enabled", havingValue="true")`. Scheduled at a fixed delay (60 s). Claims rows, checks rolling 24-hour sent count against MAIL_DAILY_CAP, sends via EmailSender, marks SENT or increments attempts (FAILED after 5, with exponential send_after back-off: 2^attempts minutes).
4. Check: unit test `OutboxSchedulerTest` with mocked EmailSender asserts: cap is enforced, failed rows increment attempts, 6th attempt sets status=FAILED.

### Slice 5 — AuthToken entity, repo, service  (commit: `feat(email): auth_tokens entity, repo, token service`)
1. Create `com.followwise.authtoken.AuthToken` @Entity mapping `auth_tokens`.
2. Create `AuthTokenRepository` extends `JpaRepository`; add `@Modifying` query to invalidate unused tokens of same user+type; query to find by hash where used_at IS NULL and expires_at > now.
3. Create `AuthTokenService`: generates 32 random bytes → base64url → SHA-256 hex hash; issues token (invalidates prior unused same-type tokens); validates token (marks used_at); calculates expiry (VERIFY_EMAIL=24h, RESET_PASSWORD=1h). Uses `TokenService.hashToken()` pattern already in place.
4. Check: `cd backend && mvn test` exits 0.

### Slice 6 — Email verification endpoints  (commit: `feat(email): email verification endpoints`)
1. In `AuthService.register()`: after saving user, enqueue `email_outbox` row (kind=VERIFY_EMAIL, subject "Verify your FollowWise email", body contains APP_BASE_URL/verify-email?token=<raw>). Calls `AuthTokenService.issue(userId, VERIFY_EMAIL)`.
2. Add to `AuthController`:
   - `POST /api/auth/verify-email` (body: `{"token":"..."}`, no auth required): validates token, sets `users.email_verified_at=now()`, returns 200.
   - `POST /api/auth/resend-verification` (authenticated): issues new token, enqueues new row, returns 204.
3. Check: `EmailVerificationIntegrationTest` — register → outbox row exists with correct kind; call verify-email with token → 200, email_verified_at set; reuse token → 400; resend → new outbox row.

### Slice 7 — Password reset endpoints  (commit: `feat(email): password reset endpoints`)
1. Add to `AuthController`:
   - `POST /api/auth/forgot-password` (body: `{"email":"..."}`, no auth): always 204. If user found: issue RESET_PASSWORD token, enqueue email. If not found: still 204 (no user enumeration).
   - `POST /api/auth/reset-password` (body: `{"token":"...","newPassword":"..."}`, no auth): validates token, validates new password via `PasswordService.validatePassword()`, re-encodes, saves, revokes all refresh token families for user via `RefreshTokenRepository.revokeAllForUser(userId, now)` (add this method), clears lockout counters (`resetFailedAttempts`), returns 200.
2. Check: `PasswordResetIntegrationTest` — forgot-password for unknown email → 204; known email → outbox row; reset with token → 200, old refresh cookie rejected; reuse token → 400.

### Slice 8 — Docs update  (commit: `docs: append D-47–D-52, tick STATUS step 3b`)
1. Append D-47–D-52 verbatim (from Decisions block below) to README.md decision log table in section 14.1.
2. In docs/STATUS.md: tick step 3b, update "Current step" to "Step 4: frontend skeleton", set "Next task" to "PLAN-0005: frontend skeleton (Phase 0, step 4)", add task log line for this plan.
3. Set this plan Status: DONE.

## Checks
- `cd backend && mvn test` exits 0, all tests pass (including new integration tests).
- `docker build -t followwise-backend backend/` exits 0.
- `docker run --rm -e PORT=9090 -e MAIL_TRANSPORT=log -e JWT_SECRET=... -e DATABASE_URL=... ... -p 9090:9090 followwise-backend` → `GET /health` returns 200.
- `GET /v3/api-docs` returns 200 when SPRINGDOC_API_DOCS_ENABLED=true.
- App starts with `MAIL_TRANSPORT=log` and local profile without error.
- App refuses to start with `MAIL_TRANSPORT=log` and `prod` profile (IllegalStateException logged).
- Outbox rows are inserted on registration (verify via integration test).

## Acceptance criteria
- [ ] `mvn test` passes with no failures.
- [ ] Email verification flow works end-to-end in integration test.
- [ ] Password reset flow revokes refresh tokens and clears lockout.
- [ ] Log transport is blocked in prod profile.
- [ ] SMTP transport validates required env vars at startup.
- [ ] Outbox scheduler enforces daily cap and retries with backoff.
- [ ] D-47–D-52 appended to README decision log.
- [ ] docs/STATUS.md step 3b ticked; Next task = PLAN-0005.

## Risks and assumptions
- `spring-boot-starter-mail` is in the Spring Boot 4.1 BOM (confirmed: listed at https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html); no explicit version needed.
- `SKIP LOCKED` is supported by PostgreSQL 9.5+; Supabase Postgres 16 qualifies.
- Tests use the log transport (MAIL_TRANSPORT=log, local profile); no real SMTP call in CI.
- `RefreshTokenRepository.revokeAllForUser` is a new @Modifying query; plan assumes the entity already has `revoked_at` (confirmed from PLAN-0003 report).
- PasswordService validates 10–72 UTF-8 bytes (confirmed in source); plan inherits this range per D-44 (10–128 stated in D-44 but implementation is 72 bytes — executor should not change the validation logic; raise BLOCKED if the discrepancy needs resolving).

## Decisions needed
None. D-47–D-52 are pre-approved.

## STATUS.md update (executor does this)
- Task log line: `| <date> | PLAN-0004: email layer, verification, password reset | <model> | Done |`
- Tick: `- [x] 3b. Email verification, password reset, email adapter (PLAN-0004)`
- Current step → "Step 4: frontend skeleton"
- Next task → "PLAN-0005: frontend skeleton (Phase 0, step 4)"

---
## Decisions (executor appends these verbatim to README.md section 14.1 decision log)

| ID | Decision |
|---|---|
| D-47 | Email is sent only through an outbox table (email_outbox: id, kind, to_address, subject, body_text, status QUEUED\|SENT\|FAILED, attempts, last_error, send_after, sent_at, created_at). Request handlers only enqueue. A scheduled worker (runs only when SCHEDULER_ENABLED=true) claims rows with FOR UPDATE SKIP LOCKED, enforces MAIL_DAILY_CAP over a rolling 24 hours, sends, and retries failures with backoff up to 5 attempts. Account emails (verification, reset) are sent before any later bulk reminders. |
| D-48 | An EmailSender interface with a Gmail SMTP implementation (Spring Mail) configured only from the MAIL_* environment variables. MAIL_TRANSPORT=smtp\|log selects the transport. The log transport prints the email (including links) and is allowed only in the local profile; the app must refuse to start if log transport is used with the prod profile, or if smtp is selected with missing host, username, or password. From address is MAIL_FROM_ADDRESS, Reply-To is MAIL_REPLY_TO. Plain-text emails only in v1. |
| D-49 | One-time account tokens: table auth_tokens (id, user_id, type VERIFY_EMAIL\|RESET_PASSWORD, token_hash char(64) unique, expires_at, used_at, created_at). Token is 32 random bytes, base64url, stored only as a SHA-256 hash, single use. Verification expires in 24 hours, reset in 1 hour. Issuing a new token of a type invalidates older unused tokens of that type for the user. Links are APP_BASE_URL/verify-email?token=... and APP_BASE_URL/reset-password?token=.... |
| D-50 | Email verification: users.email_verified_at (nullable). Registration still logs the user in immediately and enqueues a verification email. Unverified users may use the app but see a banner (frontend enforces). POST /api/auth/verify-email (no auth, body token) validates token and sets email_verified_at. POST /api/auth/resend-verification (authenticated) issues a new token and enqueues a new email. |
| D-51 | Password reset: POST /api/auth/forgot-password (no auth, body email) always returns 204 — no user enumeration. If the user exists a RESET_PASSWORD token is issued and the link is emailed. POST /api/auth/reset-password (no auth, body token+newPassword) validates the token, re-encodes the password, revokes all refresh token families for the user, and clears lockout counters. |
| D-52 | Tests for email flows use the log transport (MAIL_TRANSPORT=log, local profile). No real SMTP call is made in tests or CI. The outbox scheduler is disabled in tests (SCHEDULER_ENABLED=false). |

## Orchestrator amendments (binding, override anything above that conflicts)
A. Stale text
1. Replace every `mvn test -pl . -Dtest=SmokeTest` with `cd backend && mvn test`.
2. No password discrepancy: D-44 in README is already 10 to 72 bytes. Reuse PasswordService unchanged.
3. Bind the scheduler switch to the existing env var SCHEDULER_ENABLED. Do not invent app.scheduler.enabled unless it is mapped to SCHEDULER_ENABLED.
B. Restore dropped behavior
1. resend-verification: at most 1 per 60 seconds and 5 per day per user (count auth_tokens rows); over the limit returns 429 problem+json with Retry-After.
2. forgot-password: at most 3 reset emails per hour per email (count RESET_PASSWORD tokens in the last hour). Extra requests still return 204 and enqueue nothing.
3. reset-password also enqueues a PASSWORD_CHANGED notice to the account address. Wrong, expired, or used tokens return one identical 400 problem body.
4. GET /api/me adds emailVerified (boolean).
5. SecurityConfig: permit exactly three more POST paths: /api/auth/verify-email, /api/auth/forgot-password, /api/auth/reset-password. resend-verification stays authenticated.
C. Email layer
1. The log transport is allowed ONLY when the active profile is local or test. Any other profile, including none, dev, or prod, with MAIL_TRANSPORT=log fails startup. smtp with a blank MAIL_HOST, MAIL_USERNAME, or MAIL_PASSWORD fails startup. MAIL_TRANSPORT must be exactly smtp or log; blank or anything else fails startup.
2. AUTH_COOKIE_SECURE must be exactly "true" or "false"; blank or anything else fails startup (test it).
3. Outbox order: account kinds (VERIFY_EMAIL, RESET_PASSWORD, PASSWORD_CHANGED) first, then oldest first. Claim with a native query ending in FOR UPDATE SKIP LOCKED inside a transaction; batch size 10.
4. SMTP connect, read, and write timeouts of 10 seconds.
5. After a row becomes SENT or FAILED, overwrite body_text with '[redacted]'. Email bodies are never logged except by the log transport.
6. The verification email is enqueued in the same transaction as registration; a failure rolls both back.
D. Required tests (all must exist)
- Handlers never send inline (a mocked EmailSender has no calls until the worker runs).
- Cap enforced with the overflow left QUEUED, oldest first, account kinds first.
- Retry with backoff, then FAILED at 5 attempts, and the body is redacted afterward.
- Two concurrent claims never return the same row.
- Token hashing, expiry (24 h and 1 h), single use, and replacement of older unused tokens.
- verify-email happy and failure paths.
- Resend limits (60 second rule and daily rule).
- forgot-password response is identical for known and unknown emails, and the 4th request within an hour enqueues nothing.
- Reset revokes all refresh families, clears the lockout, and enqueues the notice.
- Startup failures: log transport outside local/test, smtp with missing settings, bad MAIL_TRANSPORT, bad AUTH_COOKIE_SECURE.
- SmtpEmailSender builds the right message (mocked JavaMailSender).
- No token or email body appears in captured logs under the smtp transport.
E. The report must list as "Founder manual verification" (the founder does these after merge): the Gmail app password works over smtp.gmail.com port 587; a real verification email arrives; the From header may show the Gmail address even if MAIL_FROM_ADDRESS differs; the limit is 500 recipients per rolling 24 hours; check the spam folder.
F. Known gaps to record in the report: access tokens stay valid up to 15 minutes after a reset; an outbox cleanup job is needed later; the frontend must serve /verify-email and /reset-password with Referrer-Policy: no-referrer.
G. Replace the plan's Decisions block. Append to README 14.1 verbatim:
D-47 Email is sent only through an outbox table (email_outbox: id, kind, to_address, subject, body_text, status QUEUED|SENT|FAILED, attempts, last_error, send_after, sent_at, created_at). Request handlers only enqueue. A scheduled worker (runs only when SCHEDULER_ENABLED=true) claims rows with FOR UPDATE SKIP LOCKED, enforces MAIL_DAILY_CAP over a rolling 24 hours, sends, and retries with backoff up to 5 attempts. Account emails go before bulk reminders. After SENT or FAILED, body_text is overwritten with '[redacted]' so raw token links are not kept.
D-48 EmailSender interface with a Gmail SMTP implementation (Spring Mail) configured only from MAIL_* variables. MAIL_TRANSPORT=smtp|log. The log transport prints the email including links and is allowed only in the local or test profile; startup fails for log in any other profile, for smtp with missing host, username, or password, and for any other MAIL_TRANSPORT value. Plain-text emails only in v1.
D-49 Table auth_tokens (id, user_id, type VERIFY_EMAIL|RESET_PASSWORD, token_hash char(64) unique, expires_at, used_at, created_at). Token is 32 random bytes, base64url, stored only as a SHA-256 hash, single use. Verification 24 hours, reset 1 hour. A new token of a type invalidates older unused ones. Links are APP_BASE_URL/verify-email?token=... and APP_BASE_URL/reset-password?token=...
D-50 users.email_verified_at (nullable). Registration logs the user in and enqueues a verification email. Unverified users may use the app (the frontend shows a banner from /api/me emailVerified); limits on emailing third parties are enforced in later plans. POST /api/auth/verify-email; POST /api/auth/resend-verification (authenticated, 1 per 60 seconds, 5 per day).
D-51 POST /api/auth/forgot-password always returns 204; it enqueues an email only for existing accounts, at most 3 per hour per email. POST /api/auth/reset-password applies the 10 to 72 byte rule, sets the password, clears lockout, revokes all refresh families, marks the token used, and enqueues a PASSWORD_CHANGED notice. Bad, expired, or used tokens return one generic 400.
D-52 Duplicate registration keeps returning 409 (accepted enumeration trade-off; revisit with IP rate limiting).
D-53 Email tests use the log transport and mocked senders; no real SMTP in tests or CI. The scheduler is disabled in tests except where the worker itself is under test.
---
## Execution report (executor fills)
- What was done:
- Commands run and results:
- Deviations from the plan:
- Follow-ups or new risks:
