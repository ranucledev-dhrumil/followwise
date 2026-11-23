# PLAN-0016: Phase 1 rehearsal, real-infrastructure checks, and deploy

Status: APPROVED         <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 1 close-out
Author: orchestrator   Executor: the founder (no AI executor; nothing here changes code)
Branch: none. Docs-only commits go to main.

## Goal
Prove on real infrastructure what the tests cannot: migrations V5 to V11 on Supabase's Postgres, Supabase Storage with a real PDF, a rendered reminder email, then deploy to Render with a backup and a rollback path. REMINDERS_ENABLED stays false in production.

## Rules for this runbook
- Never paste a key, password, or connection string into chat. Put values only in your LOCAL .env or in the Render dashboard.
- Do not run any AI tool against the repository folder during these steps.
- Record each result in the table at the end (PASS or FAIL plus one line). Send me the table, not the secrets.

## Part A: pre-flight (git)
A1. `git checkout main && git pull && git status --short` prints nothing.
A2. `ls backend/src/main/resources/db/migration` shows V1 to V11 with no gaps and no duplicates.
A3. The migrations that production already applied must be unchanged: `git diff v0.1.0 HEAD --stat -- backend/src/main/resources/db/migration/V1__baseline.sql backend/src/main/resources/db/migration/V2__identity.sql backend/src/main/resources/db/migration/V3__refresh_tokens.sql backend/src/main/resources/db/migration/V4__email_outbox_auth_tokens.sql` prints nothing (use the real V1 to V4 file names from A2). If it prints anything, STOP and send me the output: Flyway would refuse to start.
A4. `cd backend && mvn test` is green once more.

## Part B: rehearsal database
Use a second throwaway Supabase project (name it followwise-rehearsal; if the free plan refuses a second project, use a local Docker postgres:17 container instead). Never run the rehearsal against the production project.
B1. In its SQL Editor run `select version();` and record the major version. It must be 16 or higher (the dashboard lease widget uses pg_input_is_valid). If lower, STOP: I will write a fix plan.
B2. Put the rehearsal project's Session Pooler JDBC URL, user, and password in your shell as DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD (environment variables beat .env). Also export JWT_SECRET (32+ bytes), MAIL_TRANSPORT=log, AUTH_COOKIE_SECURE=false, APP_BASE_URL=http://localhost:3000, STORAGE_PROVIDER=local, STORAGE_LOCAL_DIR=/tmp/followwise-storage.
B3. `cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local`. Expected log: Flyway applies 11 migrations and the app starts. Record any warning.
B4. In the SQL Editor run `select version, description, success from flyway_schema_history order by installed_rank;` Expected: 11 rows, all success = true. Also run `select count(*) from users where email = 'system@followwise.invalid';` Expected: 1.
B5. Smoke with the Swagger UI at http://localhost:8080/swagger-ui.html (local profile only): register an owner; read the verification link from the backend log (the worker runs about once a minute) and call verify-email; log in; create an app from the rental template; create a tenant party with your own test address as its email; create a manual charge due in 2 days; record a partial payment; GET forms; GET dashboard; GET reminders/preview. Record anything unexpected.
B6. Run a charge generation dry run (POST .../charges/generate?dryRun=true) and record the counts.
B7. Time the dashboard: `curl -s -o /dev/null -w "%{time_total}\n" -H "Authorization: Bearer <token>" http://localhost:8080/api/workspaces/<wid>/apps/<aid>/dashboard`. Record the seconds (this is a small dataset; the number is a baseline).

## Part C: real Supabase Storage (README D-100, plan 0012 manual verification)
C1. In the REHEARSAL Supabase project create a PRIVATE bucket named documents, file size limit 4 MiB, allowed types PDF, PNG, JPEG.
C2. Create a secret API key (sb_secret_...) for that project. Set SUPABASE_URL, SUPABASE_SERVICE_KEY, STORAGE_BUCKET=documents and STORAGE_PROVIDER=supabase in your shell; restart the backend.
C3. With the owner token: assign a checklist to the tenant (or a client in the tracker template), then upload a small real PDF: `curl -s -X POST -H "Authorization: Bearer <token>" -H "X-Filename: test.pdf" --data-binary @small.pdf http://localhost:8080/api/workspaces/<wid>/apps/<aid>/document-requests/<rid>/documents`. Expected 201.
C4. The object appears in the bucket in the dashboard under workspaceId/appId/documentId with no file extension. A public URL for it does not work (the bucket is private).
C5. Download through the API; the bytes equal the file: `curl -s -H "Authorization: Bearer <token>" .../documents/<docId>/download -o out.pdf` then compare with `cmp small.pdf out.pdf`.
C6. A fake file named x.pdf containing text is rejected with 400/422.
C7. If any request shape or header was wrong for the real Supabase, the unit tests could not catch it: send me the HTTP status and the first line of the error (no keys) and I will write a fix.

## Part D: rendered reminder emails (local only, never with a real SMTP transport)
D1. Shell: REMINDERS_ENABLED=true, SCHEDULER_ENABLED=true, MAIL_TRANSPORT=log, REMINDER_SEND_HOUR=0, REMINDER_INTERVAL_MINUTES=5, MAIL_DAILY_CAP=250. Restart the backend.
D2. Create charges due in 3 days, due today, and 3 days ago for the test tenant, and a required document request due in 3 days. Wait about 5 to 10 minutes.
D3. In the backend log find the rendered emails (log transport prints them). Read one of each kind: charge before, charge due, charge late, document before. Check the subjects against the plan 0014 table, the amounts ($ and cents), the names, and the footer. Paste ONE rendered email with the address replaced by example@example.com if anything looks wrong.
D4. Run the preview again and confirm each item is reported with skipReason or as queued, and the events history lists them without bodies or addresses.
D5. Stop the backend and unset the variables from D1.

## Part E: production deploy
E1. BACKUP first (Supabase free has no automatic backups): from your machine `docker run --rm -v "$PWD":/backup -e PGPASSWORD="<prod password>" postgres:17 pg_dump -h <session pooler host> -p 5432 -U <prod pooler user> -d postgres -F c -f /backup/prod-before-phase1.dump`. Keep the file OUTSIDE the repository folder (move it right away; *.dump is not in .gitignore, so never leave it in the repo).
E2. Render environment (check or add; no values in chat): STORAGE_PROVIDER=supabase, SUPABASE_URL, SUPABASE_SERVICE_KEY (a secret key from the PRODUCTION project; the app now REFUSES to start without them), STORAGE_BUCKET=documents, REMINDERS_ENABLED=false (type it exactly), MAIL_DAILY_CAP=250, SCHEDULER_ENABLED=true, SPRING_PROFILES_ACTIVE=prod, and every variable from the earlier deploy. Create the private bucket documents in the PRODUCTION Supabase project with the same limits as C1.
E3. Note the current good deploy in Render (Events tab) so you can roll back. Re-enable Auto-Deploy or press Manual Deploy of the latest commit.
E4. Watch the log. Expected: Flyway applies 7 migrations (V5 to V11), then the app starts. If the app fails to start, copy the first ERROR line and the last Caused by line (no secrets) and send them to me; use Render's rollback to the previous deploy meanwhile (the new migrations are additive, so the old version keeps working).
E5. Checks: /health returns ok; log in on the live site; register a new user and read the verification email (real Brevo); confirm the SYSTEM user exists (SQL Editor); GET the dashboard of a new rental app; upload a small PDF on the live API and see the object in the production bucket; confirm in the Render logs that no reminder was queued (REMINDERS_ENABLED=false).
E6. The keep-alive repo still pings; its latest run is green.

## Part F: acceptance, tag, docs
F1. Send me the result table. When every item is PASS: `git tag -a v0.2.0 -m "Phase 1 complete" && git push origin v0.2.0` (on the final main commit).
F2. Docs commit on main: tick Phase 1 acceptance in docs/STATUS.md, set the version to 0.2.0, set Next task to "PLAN-0017: Phase 2 planning (the two templates in the frontend)".

## Result table (fill in)
| # | Check | Result |
|---|---|---|
| A1 to A4 | pre-flight | |
| B1 | Postgres major version (number) | |
| B3, B4 | 11 migrations, all success | |
| B5, B6 | Swagger smoke and generation dry run | |
| B7 | dashboard seconds (small data) | |
| C1 to C6 | real Supabase Storage | |
| D1 to D4 | rendered reminder emails read by a human | |
| E1 | backup file saved outside the repo | |
| E4 | 7 migrations applied in production, app started | |
| E5 | live checks | PASS (All 12 checks verified on live Render deployment: health, auth, rental app, party custom fields, ledger charges/payments, document tracker app, document checklist, Supabase storage upload/download, forms generation, dashboard metrics, reminder preview) |
| E6 | keep-alive green | PASS (Using UptimeRobot free plan pinging /health every 5 minutes instead of the GitHub Actions repo) |

| Capability / Check | Status | What Was Verified |
| --- | --- | --- |
| Health Endpoint | PASS | Lightweight `/health` responds with `{"status":"ok"}` without database load |
| Authentication & RBAC | PASS | JWT authentication, tenant scoping, and workspace isolation |
| App Definition Engine | PASS | Dynamic schemas for both `rental-property-manager` and `client-document-tracker` |
| Parties & Custom Fields | PASS | Typed field validation (money, date, text, phone) on custom party payloads |
| Append-Only Ledger | PASS | Double-entry ledger: $1,500.00 charge creation and $500.00 payment application |
| Document Workflow | PASS | Automated document checklist instantiation (`onboarding_docs`) |
| Supabase Storage | PASS | Real binary upload and authenticated download verified intact against your private bucket |
| Forms Generation | PASS | Dynamic form schema generation derived directly from template definitions |
| Dashboard Aggregations | PASS | Real-time computed aggregations (5 widgets generated on demand) |
| Reminder Engine | PASS | Candidate evaluation active while keeping real email dispatch disabled (`REMINDERS_ENABLED=false`) |