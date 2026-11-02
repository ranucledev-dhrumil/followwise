# STATUS (single status log)

Update this file at the end of every task. It is the first thing every model and the founder reads.
Last updated: 2026-11-02 by Gemini 3.8 Flash

## Current
| Item | Value |
|---|---|
| Product version | **0.0.0** (pre-build, working toward v1) |
| Docs baseline | README v1.2 (decision log D-01 to D-53) |
| Current phase | **Phase 0: foundations (walking skeleton)** |
| Current step | Step 4: frontend skeleton |
| Next task | PLAN-0005: frontend skeleton (Phase 0, step 4) |

## Version scheme
- 0.x while building: 0.1.0 = Phase 0 done, 0.2.0 = Phase 1 done, 0.3.0 = Phase 2 done.
- 1.0.0 = README section 16.3 (definition of done) is met.
- Patch versions (0.1.1) for fixes inside a phase.

## Phase tracker
| Phase | Target version | Status | Notes |
|---|---|---|---|
| 0. Foundations | 0.1.0 | IN PROGRESS | Step 3 complete (Identity & email/recovery) |
| 1. Engine core | 0.2.0 | NOT STARTED | |
| 2. The two templates | 0.3.0 | NOT STARTED | Rental Property Manager, Client Document Tracker |
| 3. Private beta | 1.0.0 | NOT STARTED | Needs paid upgrades before charging (README 8.8) |
| 4. Third template + billing | 1.x | NOT STARTED | Tutor / Class Manager |
| 5. AI interview | 1.x | NOT STARTED | Provider chosen by benchmark (README 10.5) |
| 6. Growth | 2.x | NOT STARTED | UK, SMS/WhatsApp, payments, mobile |

## Phase 0 steps
- [ ] 1. Accounts and repository (founder manual tasks below, then commit the docs bootstrap)
- [x] 2. Backend skeleton (built and verified on Spring Boot 3.3.5; to be upgraded to 4.1 in PLAN-0002)
- [x] 2b. Upgrade backend to Spring Boot 4.1 (PLAN-0002)
- [x] 3a. Identity core (PLAN-0003)
- [x] 3b. Email verification, password reset, email adapter (PLAN-0004)
- [ ] 4. Frontend skeleton (Next.js, login pages, shell, /api forwarding)
- [ ] 5. Deploy end to end (Render, Supabase, Vercel) and keep-alive repo; validate README 8.9

## Founder manual tasks (Phase 0, step 1)
Tick what is done:
- [ ] Create a **private** GitHub repository `followwise`; add the bootstrap files; first commit
- [ ] Create Supabase project (note region, database password, connection string)
- [ ] Create Render account (no service yet)
- [ ] Create Vercel account (Hobby plan; link the repo in step 4)
- [ ] Gmail: turn on 2-step verification and create an app password for the sending account
- [ ] Store all secrets in a password manager (never in the repo)
- [ ] Open the repo in Antigravity; confirm it reads `AGENTS.md`
- [ ] Later (step 5): create a small **public** repo `followwise-keepalive` for the ping workflow

## Task log
| Date | Task | Done by | Result |
|---|---|---|---|
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
- Founder manual check of real Gmail sending (README plan 0004, section "Founder manual verification"): due before the 0.1.0 tag.

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