# PLAN-0006: Deploy end to end

Status: APPROVED
Phase / step: Phase 0, step 5
Author: Claude (planner)  Executor: (model that executed)
Branch: plan-0006

## Goal
Backend live on Render (free Docker), database on Supabase, frontend on Vercel (Hobby), keep-alive pinger in `followwise-keepalive`. Verify the full path sign-up → database row → email → login. Tag v0.1.0.

## Out of scope
Custom domains, Supabase Storage bucket creation (no upload code yet), Swagger (already disabled in prod).

## VERIFY results

**V1 – Supabase → Render** (supabase.com/docs, mkyong.com): Direct connections are IPv6 only; Render free is IPv4 only → use **Session Pooler** only (`aws-0-[region].pooler.supabase.com:5432`). Transaction pooler (port 6543) breaks prepared statements — do not use for Flyway/JPA. JDBC URL: `jdbc:postgresql://aws-0-[region].pooler.supabase.com:5432/postgres?sslmode=require`. Username: `postgres.[project-ref]`. HikariCP: max-pool-size=5, min-idle=1.

**V2 – Render free Docker** (render.com/docs): Root Directory = `backend/`. Render injects `PORT` (~10000); `application.properties` already reads `${PORT:8080}`. Health Check Path: `/health`. Auto-deploy on push to `main` by default. Cold start: ~30–60 s for a 512 MB Spring Boot container. `JAVA_TOOL_OPTIONS` in Dockerfile is respected.

**V3 – Vercel Hobby + Node 22** (vercel.com/docs): Node 22 LTS supported — set under Settings → General. `BACKEND_API_URL` must be a server-side env var (not `NEXT_PUBLIC_`); rewrite in `next.config.ts` reads it at build time. Vercel Hobby proxy hard timeout: **10 s**. A cold-starting Render backend takes 30–60 s → first post-sleep request 504. Keep-alive mitigates; no fix within Hobby limits.

**V4 – GitHub Actions keep-alive** (github.com/actions): Cron `*/10 * * * *` is best-effort (delays up to 15 min). Schedules disabled after **60 days with no commits**; `workflow_dispatch` still works. Fix: empty commit or manual re-enable from the Actions tab.

## Environment variable table

| Variable | Render | Vercel | Keep-alive | Source | Secret? |
|---|---|---|---|---|---|
| `DATABASE_URL` | ✓ | — | — | Supabase → Session Pooler → JDBC | **Yes** |
| `DATABASE_USER` | ✓ | — | — | `postgres.[ref]` | **Yes** |
| `DATABASE_PASSWORD` | ✓ | — | — | Supabase project password | **Yes** |
| `JWT_SECRET` | ✓ | — | — | `openssl rand -hex 32` | **Yes** |
| `MAIL_PASSWORD` | ✓ | — | — | Gmail app password | **Yes** |
| `SUPABASE_SERVICE_KEY` | ✓ | — | — | Supabase → API → service_role | **Yes** |
| `AUTH_COOKIE_SECURE` | ✓ | — | — | `true` | No |
| `APP_BASE_URL` | ✓ | — | — | Vercel production URL (no trailing slash) | No |
| `SCHEDULER_ENABLED` | ✓ | — | — | `true` | No |
| `SPRING_PROFILES_ACTIVE` | ✓ | — | — | `prod` | No |
| `MAIL_USERNAME`, `MAIL_FROM_ADDRESS`, `MAIL_REPLY_TO` | ✓ | — | — | Gmail address | No |
| `SUPABASE_URL` | ✓ | — | — | Supabase → Project URL | No |
| All other backend vars | ✓ | — | — | Defaults from `.env.example` | No |
| `BACKEND_API_URL` | — | ✓ | — | Render service URL (no trailing slash) | No |
| `NEXT_PUBLIC_APP_NAME` | — | ✓ | — | `FollowWise` | No |
| `HEALTH_URL` | — | — | ✓ (repo variable) | Render service URL | No |

## Part 1: Founder runbook (FOUNDER – dashboard steps, not done by executor)

**F-1 Supabase** → New project, note region. Expected: "Healthy" within 2 min.

**F-2 Supabase** → Connect → Session Pooler → JDBC. Copy JDBC URL (starts `jdbc:postgresql://aws-0-[region].pooler.supabase.com:5432/postgres?sslmode=require`), username (`postgres.[ref]`), password (URL-encode special chars).

**F-3** Database is brand new; `baseline-on-migrate` not needed. Flyway applies V1–V4 from scratch.

**F-4 Gmail** → Security → 2-Step Verification → App passwords → name "FollowWise". Copy 16-char password.

**F-5 Render** → New → Web Service → Docker → connect GitHub repo. Root Directory: `backend`. Health Check Path: `/health`. Plan: Free. Auto-Deploy: Yes. Expected: service URL `https://[name].onrender.com`; first build ~5 min.

**F-6 Render** → set all Render env vars from the table. Mark `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD`, `SUPABASE_SERVICE_KEY` as secrets. Leave `APP_BASE_URL` blank for now (fill after F-10). Expected: redeploy triggered.

**F-7 Render** → wait for "Server started on port …". Hit `/health` → 200 `{"status":"UP"}`. Logs show "Successfully applied 4 migration(s)".

**F-8 Vercel** → Add New Project → import repo. Root Directory: `frontend`. Framework: Next.js. Settings → General → Node.js Version: 22.x.

**F-9 Vercel** → set `BACKEND_API_URL` = Render URL (no trailing slash), `NEXT_PUBLIC_APP_NAME` = `FollowWise`. Both for Production + Preview + Development. Trigger redeploy.

**F-10 Vercel** → `curl -I https://[project].vercel.app`. Expected: security headers present. `GET /api/auth/me` → 401 (proxy working).

**F-11 Render** → set `APP_BASE_URL` = Vercel production URL. Redeploy. Expected: restarts cleanly.

**F-12 Keep-alive** → Create public GitHub repo `followwise-keepalive`. Add Appendix A workflow. Settings → Actions → Variables → new variable `HEALTH_URL` = Render URL. Run workflow_dispatch → log shows HTTP 200.

## Part 2: Repo changes (executor)

### Slice 1 – Prod properties hardening (commit: `chore: add prod datasource and mail settings`)
In `backend/src/main/resources/application-prod.properties`, add:
```
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
mail.transport=smtp
```
Check: `cd backend && mvn test` green.

### Slice 2 – Keep-alive workflow file (commit: `chore: add keepalive workflow to repo as reference`)
Create `docs/keepalive/ping.yml` with the content from Appendix A (the executor copies it verbatim from this plan). This is documentation only; the founder copies it to the `followwise-keepalive` repo.
Check: file exists and is valid YAML (`python -c "import yaml, sys; yaml.safe_load(open(sys.argv[1]))" docs/keepalive/ping.yml` or equivalent).

### Slice 3 – Decision log (commit: `docs: append D-59 to decision log`)
Append D-59 to the README decision log (section 14.1) per the Decisions block below.
Check: `Select-String "D-59" README.md` finds the row.

### Slice 4 – STATUS.md + tag (commit: `docs: Phase 0 complete, tag v0.1.0`)
Update `docs/STATUS.md`: add task log line for today, tick Phase 0 step 5 ✓, set "Current phase" to "Phase 0 complete, pending founder acceptance", set "Next task" to "PLAN-0007: Phase 1 engine core planning (App Definition schema)".
Set plan Status to DONE.
After the commit, tag: `git tag -a v0.1.0 -m "Phase 0 complete"` and push the tag.
Check: `git log --oneline -1` shows the commit; `git tag` lists `v0.1.0`.

## Acceptance checklist (founder runs after F-12)

| # | Check | Pass condition |
|---|---|---|
| 1 | GET `[render-url]/health` | 200, body contains `UP` |
| 2 | GET `[vercel-url]/api/auth/me` | 401 (proxy is working) |
| 3 | `curl -I [vercel-url]` | X-Frame-Options, X-Content-Type-Options, CSP, Referrer-Policy present |
| 4 | Register via UI | Redirect to "check your email" |
| 5 | Verification email received | Link contains Vercel URL, token in URL |
| 6 | Click verification link | Account marked verified, redirected to dashboard |
| 7 | Login in second browser | Dashboard loads, access token in memory only (DevTools → Application: no token in localStorage/sessionStorage/readable cookies) |
| 8 | Forgot-password flow | Reset email received, password updated, old session invalidated |
| 9 | Logout | HttpOnly cookie cleared |
| 10 | Flyway in Render logs | "Successfully applied 4 migration(s)" |
| 11 | Cold start | After 16-min idle: first request gets 504 (expected); subsequent requests succeed |
| 12 | Keep-alive manual run | workflow_dispatch → HTTP 200 in Actions log |
| 13 | No secrets in Render logs | Grep logs for password/secret patterns |
| 14 | Supabase pausing | Scheduler touchpoint appears in Supabase logs within 24 h |

Items 4–9 are carried over from PLAN-0004 and PLAN-0005 manual verification lists.

## Risks and assumptions

1. **Vercel 10 s timeout**: The first request after a Render cold start (30–60 s) will 504. Mitigation: keep-alive ping every 10 min. No code fix is possible within Hobby limits.
2. **Supabase 60-day pausing**: Scheduler keeps the DB active. Upgrade trigger: DB nears 400 MB.
3. **GitHub Actions 60-day disable**: Founder must commit to `followwise-keepalive` at least every 60 days. A note is added to STATUS.md.
4. **Render free hours**: 750 h/month for the whole Render workspace. One service uses ~744 h/month if always awake (keep-alive ensures this).
5. **Gmail app password**: 2-step verification must be enabled on the Gmail account before F-4.
6. Assumption: `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` are set but Supabase Storage is not yet used (no upload endpoints exist). Variables are required by the storage provider bean to start.

## Decisions needed

None. All decisions for this plan were made by the orchestrator.

---

## Appendix A: Keep-alive workflow

Copy verbatim to `followwise-keepalive/.github/workflows/ping.yml`:

```yaml
name: ping

on:
  schedule:
    - cron: '*/10 * * * *'
  workflow_dispatch:

jobs:
  ping:
    runs-on: ubuntu-latest
    steps:
      - name: Ping health endpoint
        run: |
          STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${{ vars.HEALTH_URL }}/health")
          echo "HTTP status: $STATUS"
          if [ "$STATUS" != "200" ]; then
            echo "Health check failed with status $STATUS"
            exit 1
          fi
```

---

## Decisions block (executor appends to README section 14.1)

**D-59** Deployment: backend on Render free web service (Docker, root `backend/`), database on Supabase (Session Pooler only — direct connection is IPv6 and fails on Render free). Frontend on Vercel Hobby (root `frontend/`, Node 22). Keep-alive pings `/health` every 10 min from a public repo `followwise-keepalive` using a GitHub Actions cron and `HEALTH_URL` repository variable. Version 0.1.0 tagged on `main` after founder acceptance.

---
## Orchestrator amendments (binding, override anything above that conflicts)
1. Health returns {"status":"ok"}; the proxy check is GET /api/me returning 401 (not /api/auth/me). Fix acceptance checks 1 and 2.
2. Vercel proxied-request timeout is 120 seconds (not 10). Replace check 11: after 20 minutes idle with keep-alive OFF, the first request succeeds but takes 30 to 60 seconds and the UI shows "waking up"; record the real time. Remove "expect 504". Risk 1 becomes: slow first request, not failure.
3. Env table fixes: list explicitly MAIL_TRANSPORT, MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM_NAME, MAIL_FROM_ADDRESS, MAIL_REPLY_TO, MAIL_DAILY_CAP, APP_DEFAULT_TIMEZONE, JWT_ACCESS_TTL_MINUTES, JWT_REFRESH_TTL_DAYS, AUTH_COOKIE_SECURE=true, SCHEDULER_ENABLED=true, SPRING_PROFILES_ACTIVE=prod. Do NOT set SUPABASE_SERVICE_KEY or SUPABASE_URL on Render (no storage code exists; least privilege). The app must start without them; if it does not, report BLOCKED and describe the bug. Delete assumption 6.
4. Slice 1: remove the invented mail.transport property. Keep the Hikari settings (max 5, min idle 1).
5. The executor does NOT create or push the v0.1.0 tag. The founder tags main after acceptance and merge. Slice 4 ends at the STATUS update. D-59 text stays.
6. Runbook order: add all Render env vars on the service creation screen (APP_BASE_URL temporary), deploy, then set BACKEND_API_URL on Vercel, deploy, then set APP_BASE_URL to the stable Vercel production URL and redeploy Render. Test only via the stable production URL: the Origin check compares exactly, so per-deployment URLs will return 403 on refresh and logout.
7. Supabase: copy the exact pooler host from the dashboard (it may start with aws-0 or aws-1). DATABASE_USER and DATABASE_PASSWORD are separate variables, so no URL-encoding is needed. Remove that note.
8. New slice: frontend/next.config.ts throws at build time when process.env.VERCEL is set and BACKEND_API_URL is empty (no silent fallback to localhost on Vercel). Local builds are unaffected. Check: `cd frontend && npm run lint && npm run typecheck && npm test && npm run build`.
9. Keep-alive workflow (Appendix A): replace the curl command with `curl -fsS --max-time 90 --retry 2 --retry-delay 10 -o /dev/null "${{ vars.HEALTH_URL }}/health"`, add `timeout-minutes: 5` on the job and `permissions: {}` at workflow level. Add to STATUS.md "Known gaps": push a commit to followwise-keepalive at least every 60 days.
10. Acceptance fixes: check 4 expects landing on /app with the unverified banner; check 14 becomes "Supabase dashboard shows the project Active (not paused) after 24 hours".
11. Add a check that backend/.dockerignore excludes .env, target/ and .git.
12. EMAIL (variant C: try Gmail SMTP on the free tier first, with a fallback)
a. Config as planned: MAIL_TRANSPORT=smtp, MAIL_HOST=smtp.gmail.com, MAIL_PORT=587, MAIL_USERNAME, MAIL_PASSWORD (Gmail app password), MAIL_FROM_ADDRESS, MAIL_REPLY_TO, MAIL_DAILY_CAP=400.
b. New runbook step F-7b, immediately after the Render backend is healthy and BEFORE any Vercel work:
   - curl -s -X POST https://<render-url>/api/auth/register -H "Content-Type: application/json" -d '{"email":"<your+fwtest1@gmail.com>","password":"<12+ char test password>"}' (use the exact field names from the Swagger or AuthController request record; Swagger is disabled in prod, so check the controller).
   - Wait 2 minutes (worker interval is 60 seconds).
   - In the Supabase SQL editor run: select kind, status, attempts, last_error, created_at from email_outbox order by created_at desc limit 5;
   - PASS: status SENT and the email arrives in your inbox (check spam).
   - FAIL: status QUEUED with attempts rising, or FAILED, and last_error mentions a connect or read timeout. That means SMTP is blocked. STOP the runbook, record the error text in STATUS.md, and the founder chooses A (paid Render instance, about $7/month) or B (domain plus an HTTPS email provider adapter, which gets its own plan) before continuing.
c. The result of F-7b goes in the Execution report and in the acceptance checklist as item 0.
d. Do not delete the test user afterward; it is reused for acceptance checks 4 to 9 (use a fresh plus-address alias if verification state matters).

---
## Execution report (executor fills)

Date:
Executor model:
Deviations from plan:
Exact dependency versions changed:
Slice 1 check result:
Slice 2 check result:
Slice 3 check result:
Slice 4 check result:
Acceptance checklist result (founder):
