# STATUS (single status log)

Update this file at the end of every task. It is the first thing every model and the founder reads.
Last updated: 2026-11-04 by Gemini 3.8 Flash (PLAN-0007)

## Current
| Item | Value |
|---|---|
| Product version | **0.1.0 pending** (Phase 0 built; tag v0.1.0 to be re-created on the final main commit after acceptance) |
| Docs baseline | README v1.2 (decision log D-01 to D-66) |
| Current phase | **Phase 1: Engine core** |
| Current step | Phase 1, step 1b |
| Next task | PLAN-0008: apps and definition versions API (Phase 1, step 1b) |

## Version scheme
- 0.x while building: 0.1.0 = Phase 0 done, 0.2.0 = Phase 1 done, 0.3.0 = Phase 2 done.
- 1.0.0 = README section 16.3 (definition of done) is met.
- Patch versions (0.1.1) for fixes inside a phase.

## Phase tracker
| Phase | Target version | Status | Notes |
|---|---|---|---|
| 0. Foundations | 0.1.0 | BUILT, ACCEPTANCE PENDING | All 5 steps built; live on Render, Supabase, Vercel |
| 1. Engine core | 0.2.0 | IN PROGRESS | |
| 2. The two templates | 0.3.0 | NOT STARTED | Rental Property Manager, Client Document Tracker |
| 3. Private beta | 1.0.0 | NOT STARTED | Needs paid upgrades before charging (README 8.8) |
| 4. Third template + billing | 1.x | NOT STARTED | Tutor / Class Manager |
| 5. AI interview | 1.x | NOT STARTED | Provider chosen by benchmark (README 10.5) |
| 6. Growth | 2.x | NOT STARTED | UK, SMS/WhatsApp, payments, mobile |

## Phase 0 steps
- [x] 1. Accounts and repository
- [x] 2. Backend skeleton (built and verified on Spring Boot 3.3.5; to be upgraded to 4.1 in PLAN-0002)
- [x] 2b. Upgrade backend to Spring Boot 4.1 (PLAN-0002)
- [x] 3a. Identity core (PLAN-0003)
- [x] 3b. Email verification, password reset, email adapter (PLAN-0004)
- [x] 4. Frontend skeleton (Next.js, login pages, shell, /api forwarding)
- [x] 5. Deploy end to end (Render, Supabase, Vercel) and keep-alive repo; validate README 8.9

## Phase 1 steps
- [x] 1a. App Definition schema and validator (PLAN-0007)
- [ ] 1b. Apps and definition versions API (PLAN-0008)
- [ ] 1c. Parties and records with custom fields
- [ ] 1d. Charges and ledger
- [ ] 1e. Documents and storage
- [ ] 1f. Reminder engine
- [ ] 1g. Forms and dashboard generation

## Founder manual tasks (Phase 0)
- [x] Private GitHub repository `followwise` with the bootstrap files
- [x] Supabase project (session pooler connection)
- [x] Render account and service (free, Docker, root `backend/`)
- [x] Vercel account and project (Hobby, root `frontend/`)
- [x] Email: Brevo SMTP relay on port 2525 (Render free blocks 25/465/587); sender verified; first real email SENT
- [ ] Revoke the old Gmail app password (no longer used; it was exposed in a shared archive on 2026-11-03)
- [ ] Rotate JWT_SECRET (local, and on Render if the same value is used)
- [x] Public repo `followwise-keepalive` created with the ping workflow
- [ ] Keep-alive: confirm a manual run shows HTTP 200 after the backend is awake (first run timed out on a cold start)
- [ ] Store all secrets in a password manager (never in the repo or in shared archives)
- [ ] Open the repo in Antigravity; confirm it reads `AGENTS.md`

## Acceptance checklist (PLAN-0006), fill in pass or fail
| # | Check | Result |
|---|---|---|
| 0 | Real email SENT through Brevo | PASS |
| 1 | GET /health on Render returns {"status":"ok"} | |
| 2 | GET /api/me through the Vercel URL returns 401 | |
| 3 | Response headers on the live site match D-57 (curl -I) | |
| 4 | Register on the live site; land on /app with the unverified banner | |
| 5 | Verification email arrives; link uses the Vercel URL | |
| 6 | Verification link works; banner disappears after reload | |
| 7 | Login in a second browser; no token in localStorage, sessionStorage, or readable cookies | |
| 8 | Forgot and reset password; old session rejected | |
| 9 | Logout clears the cookie | |
| 10 | Render logs show 4 migrations applied | |
| 11 | After 20 minutes idle with keep-alive off: first request succeeds (30 to 60 s) and the UI shows "waking up" | |
| 12 | Keep-alive manual run shows HTTP 200 | |
| 13 | No secrets or tokens in Render logs | |
| 14 | Supabase project shows Active (not paused) after 24 hours | |

## Task log
| Date | Task | Done by | Result |
|---|---|---|---|
| 2026-11-04 | PLAN-0007: App Definition schema and validator (Phase 1, step 1a) | Gemini 3.8 Flash | Done |
| 2026-11-03 | Repo review before Phase 1: no secrets in git history; found .env in shared archive (rotate), v0.1.0 tag on wrong commit, last commit message mislabeled, docs missing Brevo (D-60), STATUS stale | Orchestrator | Done |
| 2026-11-03 | Email switched from Gmail SMTP to Brevo SMTP relay on port 2525 (Render free blocks 25/465/587); first real email SENT | Founder | Done |
| 2026-11-03 | PLAN-0006: deploy end to end (Phase 0, step 5) | Gemini 3.8 Flash | Done |
| 2026-11-02 | PLAN-0005: frontend skeleton (Next.js 16, auth forms, recovery, shell, /api proxy) | Gemini 3.8 Flash | Done |
| 2026-11-02 | PLAN-0004: email layer, verification, password reset | Gemini 3.8 Flash | Done |
| 2026-11-02 | PLAN-0003: identity core (users, workspaces, memberships, auth, tokens, tenant-isolation tests) | Gemini 3.8 Flash | Done |
| 2026-10-23 | PLAN-0002: upgraded backend to Spring Boot 4.1.1, fixed compose security | Gemini 3.8 Flash | Done |
| 2026-10-23 | PLAN-0001 reviewed by orchestrator. Accepted with conditions: Spring Boot 3.3.5 is end-of-life (all 3.x are), so upgrade to 4.1 (D-38); added D-39 (UTC), D-40 (Postgres host port); AGENTS.md rules 15 and 16 added | Orchestrator | Done |
| 2026-10-23 | PLAN-0001: backend skeleton created and verified | Gemini 3.8 Flash | Done |
| 2026-10-22 | PLAN-0001 drafted by Claude (planner), reviewed by orchestrator; decided Maven and Docker for Render (D-36, D-37); fixed .env.example inline comments | Orchestrator | Done |
| 2026-10-22 | Brief finalized (v1.2); docs bootstrap created: README, AGENTS, STATUS, DESIGN, plan template, .env.example, folder placeholders | Orchestrator | Done |

## Open decisions
- Plan prices and free-tier user limits: open on purpose (README 13.3).
- AI provider: open until Phase 5 (README 10.5).
- UI kit (Tailwind + shadcn/ui proposed in docs/DESIGN.md): confirm at Phase 0, step 4.
- Email sender: Brevo with a Gmail address as From will often land in spam (DMARC). Buy a domain and verify it in Brevo before public launch (no code change).

## Blockers
None.

## Rules for updating this file
- The executor adds one task-log line per finished plan, ticks the step, updates "Current" and "Next task".
- Only the orchestrator changes the phase tracker and version scheme.
- Keep it short. Details live in the plan files.

## Known gaps (not blockers)
- Access tokens stay valid up to 15 minutes after a password reset.
- Outbox cleanup job for old SENT rows is needed.
- Outbox worker holds its DB transaction while sending (claim, release, send, update later).
- Frontend must serve /verify-email and /reset-password with Referrer-Policy: no-referrer (D-57).
- Duplicate registration returns 409 (enumeration trade-off, D-52).
- Push a commit to followwise-keepalive at least every 60 days to keep the GitHub Actions schedule active.
- Tailwind is wired through a custom Turbopack rule (`@tailwindcss/turbopack`) with no PostCSS config. It works, but verify it against the official Tailwind v4 setup before upgrading Next.js or Tailwind.
- Line endings are CRLF in the repo; add `.gitattributes` with `* text=auto eol=lf`.
- Code defaults still point at Gmail (`mail.host`) and a daily cap of 400; Brevo free allows 300 per day. Render must set MAIL_DAILY_CAP=250. Change the defaults in a small later plan.
- The CSP uses 'unsafe-inline' for scripts; move to nonces later.
- Commit 31b527b is labeled "feat: frontend skeleton (PLAN-0005)" but contains the PLAN-0006 changes. Not rewritten; use correct messages from now on.
