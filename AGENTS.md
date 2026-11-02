# AGENTS.md: rules for every AI model working on FollowWise

Read this file first in every session, then `docs/STATUS.md`. Open other docs only when this file or the task tells you to. Binding for all models.

## 1. Project in brief
FollowWise is a web platform that gives small businesses a working management app (rent tracking, client document chasing) built from fixed, tested blocks. **AI generates configuration, never code.**
Stack: Java 21 + Spring Boot 4.1 (`backend/`), PostgreSQL on Supabase, Next.js + TypeScript on Vercel (`frontend/`). Full brief and decision log: `README.md`. UI rules: `docs/DESIGN.md`. Progress: `docs/STATUS.md`.

## 2. Roles (explicit and binding)

| Who | Does | Never does |
|---|---|---|
| **Orchestrator** (the Claude chat, outside the repo) | Decisions, reviews, task briefs, doc content | n/a (not in the repo) |
| **Claude models in Antigravity** | Analysis and implementation PLANS only | Any code change. Executing a plan. |
| **Non-Claude models (Gemini)** | Execute APPROVED plans, change code, update STATUS | Plan new scope. Execute a plan that is not APPROVED. |

### If you are a Claude model
- You may: read files and write plan files in `docs/plans/` only.
- You must not: create, edit, or delete any file outside `docs/plans/`; run commands that change state (install, build, migrate, git commit or push, deploy); start executing a plan; write production code in a plan (illustrative snippets of 15 lines or fewer are allowed).
- If asked to implement, reply in one line: "Planner only. Switch to a Gemini model to execute docs/plans/PLAN-NNNN." Then stop.

### If you are a non-Claude model
- Execute only plans whose header says `Status: APPROVED`. Otherwise stop.
- Follow the plan exactly. If it is wrong or unclear, set `Status: BLOCKED`, write the reason, and stop. Do not improvise.
- Fallback: if Claude is unavailable, you may write plans in the same format. A human must still approve them.

## 3. Workflow
1. Orchestrator writes the task brief (a prompt).
2. Claude writes `docs/plans/PLAN-NNNN-slug.md` from `docs/plans/TEMPLATE.md`, `Status: DRAFT`.
3. The founder reviews and changes the status to `APPROVED`.
4. The executor works on branch `plan-NNNN`, fills the plan's Execution report, updates `docs/STATUS.md`, sets `Status: DONE`.
5. The founder takes the Execution report to the orchestrator for review, then the next brief.
Plan numbers are sequential, four digits.

## 4. Token-saving rules (binding for Claude models, good practice for all)
- Read only: this file, `docs/STATUS.md`, and the files or README sections the task names (for example "README section 8.7"). Never read the whole README unless told to.
- No repository-wide scans. No listing or reading `node_modules`, `target`, `build`, `.next`, lock files, or generated files. Use targeted file reads and searches.
- Do not re-read a file already in context. Do not paste large files or long code back into replies or plans.
- One plan per task, 150 lines or fewer. Use file paths and short numbered steps. No restating of requirements, no background lectures.
- Give one recommendation with a one-line trade-off. List alternatives only if asked.
- Ask at most one batched clarification (up to 3 questions) and only if it blocks the plan. Otherwise state an assumption in the plan.
- Write the plan once. No extra review passes.
- Finish with a summary of 5 lines or fewer plus any DECISION NEEDED items.
- If a task is simple (docs edits, renames, boilerplate), say so and recommend a cheaper model.

## 5. Engineering rules
1. Never generate or execute code on behalf of end customers. Per-customer variation is configuration.
2. Validate every App Definition against its JSON schema on every write. Keep the schema versioned.
3. Add capabilities as reusable blocks, not template-specific code.
4. Backend is independent of the frontend. Everything is available through documented REST endpoints (OpenAPI).
5. Scope every query by workspace and app through the shared data-access layer. Write tests that try to cross tenant boundaries.
6. Money: integer minor units plus a currency code. The ledger is append-only. Corrections are reversing entries.
7. Every schema change is a Flyway migration. Never edit a deployed migration.
8. External services (email, storage, AI, billing) sit behind interfaces so providers can be swapped.
9. Read every external setting from environment variables. Never hard-code credentials, sender addresses, or hostnames. Env names live in `.env.example`.
10. Reminders are idempotent and observable. Enforce the daily email cap in code and queue the overflow. Never send from a request handler.
11. Never write to local disk for anything that must survive. The free host restarts at any time.
12. The health endpoint is lightweight with no database call. The scheduler touches the database at least once a day.
13. Build the permission layer first: roles Owner, Staff, Portal user. Staff invitation screens are not in v1.
14. Build thin vertical slices before breadth. Tests for permissions and ledger math come before the features that depend on them.
15. Time: JVM default time zone UTC; store all timestamps in UTC; convert only for display and reminder scheduling.
16. Dependencies: before adding or pinning any framework or library version, check that it is currently supported (not end-of-life) and compatible with Spring Boot 4.1. Record exact versions in the plan report.
17. Stay inside v1 scope (README section 7). Anything else needs a decision from the orchestrator.

## 6. Security and data
- Never commit secrets or `.env` files. Add new variables to `.env.example` with no values.
- Do not collect highly sensitive identifiers (full SSNs, bank logins, health data) in v1.
- Do not log personal data or document contents.

## 7. Git
- Branch per plan: `plan-NNNN`. Small commits with clear messages (`feat:`, `fix:`, `docs:`, `chore:`, `test:`).
- Every plan branch starts from an up-to-date `main`. Never stack one plan branch on another.
- Merge to `main` (pull request) only after the orchestrator has reviewed the Execution report and the founder has run the checks. Delete the branch after merging.
- Docs-only updates from the orchestrator (README, STATUS, AGENTS, plan files) are committed directly to `main`.
- Never force-push. Never rewrite shared history. Never commit generated or build output.

## 8. Executor checklist before setting DONE
- Plan's checks pass and results are recorded in the Execution report.
- `docs/STATUS.md` updated: task log line, step checkbox, version if changed, "Next task".
- README decision log updated if (and only if) the plan changed a decision.

## 9. Stop and write DECISION NEEDED when
- The task conflicts with the README decision log.
- It needs a new paid service or a heavy new dependency.
- It changes money, ledger, or tenant-scoping rules.
- It needs something outside v1 scope.
Put the question in the plan under "Decisions needed" and stop. The founder takes it to the orchestrator.