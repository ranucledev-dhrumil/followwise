# PLAN-0005: Frontend skeleton

Status: DONE
Phase / step: Phase 0, step 4
Author: Gemini (Antigravity planner)   Executor: Gemini 3.8 Flash
Branch: plan-0005

## Goal
Create a Next.js 16 web app in `frontend/` with sign-up, login, email verification, password reset, a protected `/app` shell with workspace list, and an `/api` proxy to the backend. No business features, no deployment.

## Out of scope
- Auth, users, entities, billing, email sending, storage
- E2E tests, deployment, CI/CD changes
- Any backend changes

## Inputs
- `docs/DESIGN.md`, `docs/plans/PLAN-0005-frontend-skeleton.md` (this file)
- `README.md` sections 8.2, 8.9, 9, 11; decision log D-30, D-43, D-46, D-49, D-50, D-51
- `.env.example` (env var names only)
- Backend API shapes: `AuthController.java`, `WorkspaceController.java` (request/response only)

## Versions (verified 2026-11-02)
| Package | Version | Source |
|---|---|---|
| Node.js LTS | 26.x (Active LTS, Lithium) | https://nodejs.org/en/about/previous-releases |
| next | 16.4.3 | https://www.npmjs.com/package/next |
| react / react-dom | 19.x (bundled with next@16) | https://www.npmjs.com/package/react |
| tailwindcss | 4.x (CSS-first; postcss plugin `@tailwindcss/postcss`) | https://tailwindcss.com/docs/installation |
| shadcn/ui | latest CLI (`npx shadcn@latest`) — copies source | https://ui.shadcn.com/docs/installation/next |
| vitest | 3.x | https://vitest.dev/guide/ |
| @testing-library/react | 16.x | https://testing-library.com/docs/react-testing-library/intro/ |
| @vitejs/plugin-react | latest | https://www.npmjs.com/package/@vitejs/plugin-react |
| vite-tsconfig-paths | latest | https://www.npmjs.com/package/vite-tsconfig-paths |

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| `frontend/` | scaffold | `create-next-app` output |
| `frontend/next.config.ts` | create | rewrites, security headers |
| `frontend/src/lib/api.ts` | create | typed fetch, token store, refresh |
| `frontend/src/middleware.ts` | create | protect `/app/*`, redirect on 401 |
| `frontend/src/app/(auth)/` | create | login, register, verify-email, forgot-password, reset-password pages |
| `frontend/src/app/app/` | create | protected shell layout + page |
| `frontend/.env.example` | create | frontend env vars (names only) |
| `.env.example` | edit | add comment on `BACKEND_API_URL` if missing |
| `README.md` | edit | append D-54–D-58 to §14.1 decision log |
| `docs/STATUS.md` | edit | tick step 4, add task log line, update Next task |
| `docs/plans/PLAN-0005-frontend-skeleton.md` | edit | Status: DONE, fill execution report |

## Steps

### Slice 1 — Scaffold, config, proxy (commit: `feat(frontend): scaffold Next.js 16 with Tailwind and shadcn`)
1. `cd frontend && npx create-next-app@latest . --typescript --app --src-dir --tailwind --eslint --import-alias "@/*"` (answer yes to all prompts).
2. Add `"engines": { "node": ">=26.0.0" }` to `frontend/package.json`.
3. Edit `frontend/next.config.ts`: add `/api/:path*` → `${BACKEND_API_URL}/:path*` rewrite; add security headers (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`; override to `no-referrer` on `/verify-email` and `/reset-password`).
4. Create `frontend/.env.example` with `BACKEND_API_URL=` and `NEXT_PUBLIC_APP_NAME=FollowWise` (names only, no values).
5. Run `npx shadcn@latest init` (choose Neutral base, CSS variables yes); add components: `npx shadcn@latest add button input label alert badge`.
6. **Check:** `npm run build` exits 0; `npm run lint` exits 0.

### Slice 2 — API client library (commit: `feat(frontend): API client with token store and refresh`)
1. Create `frontend/src/lib/api.ts`: in-memory `accessToken` variable; `setToken`/`getToken` helpers; `apiFetch(path, init)` wrapper that adds `Authorization: Bearer <token>` when set, handles 401 by calling `/api/auth/refresh` once (with `X-FollowWise-Client: web` header) then retrying; signs out on second 401.
2. Create `frontend/src/lib/auth.ts`: `signOut()` calls `POST /api/auth/logout` (with `X-FollowWise-Client: web`), clears token, redirects to `/login`.
3. Create `frontend/src/lib/api.test.ts`: Vitest unit tests — token is stored in memory, refresh is called on 401, second 401 triggers sign-out.
4. Create `frontend/vitest.config.mts` with jsdom environment, `@vitejs/plugin-react`, `vite-tsconfig-paths`, setupFiles pointing to `vitest.setup.ts`; create `vitest.setup.ts` importing `@testing-library/jest-dom/vitest`.
5. Add `"test": "vitest run"` and `"typecheck": "tsc --noEmit"` scripts to `package.json`.
6. **Check:** `npm test` exits 0; `npm run typecheck` exits 0.

### Slice 3 — Auth forms (commit: `feat(frontend): register and login pages`)
1. Create `frontend/src/app/(auth)/layout.tsx`: centered card, Inter font, DESIGN.md tokens via Tailwind classes (primary `#2F4BC0`, background white, text `#1B1F27`).
2. Create `/register` page: email + password fields (hint: 10–72 bytes), calls `POST /api/auth/register`; on success stores token via `setToken`, redirects to `/app`; displays `detail` field from problem+JSON errors.
3. Create `/login` page: same pattern, calls `POST /api/auth/login`.
4. Create unit tests for form validation (password length, email format).
5. **Check:** `npm test` exits 0; `npm run typecheck` exits 0.

### Slice 4 — Account recovery pages (commit: `feat(frontend): email verification and password reset pages`)
1. Create `/verify-email` page: reads `?token=` query param, calls `POST /api/auth/verify-email`, then removes param via `history.replaceState`. Page header: `Referrer-Policy: no-referrer` (set in `next.config.ts` header override).
2. Create `/forgot-password` page: email input, calls `POST /api/auth/forgot-password`, shows generic success message regardless of outcome.
3. Create `/reset-password` page: reads `?token=`, password input with 10–72 byte hint, calls `POST /api/auth/reset-password`, removes `?token=` via `history.replaceState`. Header: `Referrer-Policy: no-referrer`.
4. **Check:** `npm run typecheck` exits 0; `npm run build` exits 0.

### Slice 5 — Protected `/app` shell (commit: `feat(frontend): protected app shell with workspace list`)
1. Create `frontend/src/middleware.ts`: if no access token in cookie/header (use a server-readable `auth_present` marker cookie set on login), redirect to `/login`. Matcher: `/app/:path*`.
2. Create `frontend/src/app/app/layout.tsx`: sidebar (desktop ≥1024 px) with nav items from DESIGN.md §4; bottom bar (mobile <640 px); max-width 1120 px content area; logout button calls `signOut()`.
3. Create `frontend/src/app/app/page.tsx`: calls `GET /api/me` and `GET /api/workspaces`; shows workspace list; "Waking up, one moment" skeleton state if first request takes >3 s (auto-retry up to 3 times); email-not-verified banner when `emailVerified === false` with resend button calling `POST /api/auth/resend-verification`; honors 429 `Retry-After` header (disable button and show countdown).
4. **Check:** `npm run build` exits 0; `npm test` exits 0.

### Slice 6 — Docs update (commit: `docs: append D-54–D-58, tick STATUS step 4`)
1. Append D-54–D-58 to `README.md` §14.1 decision log (exact wording from Decisions block below).
2. Update `docs/STATUS.md`: add task log line (date, PLAN-0005, done), tick Phase 0 step 4 checkbox, set Next task = PLAN-0006.
3. Set `Status: DONE` in this plan file and fill the Execution report.

## Local run instructions
```bash
cd frontend
cp .env.example .env.local
# Set BACKEND_API_URL=http://localhost:8080 in .env.local
npm install
npm run dev        # http://localhost:3000
npm test           # unit tests
npm run typecheck  # type check
npm run build      # production build
```
Backend must be running locally with `CORS` unrestricted or using the Next.js proxy (always use the proxy at `/api/*`).

## Checks
```bash
npm run lint        # exits 0
npm run typecheck   # exits 0
npm test            # exits 0, all tests pass
npm run build       # exits 0
# Manually: open http://localhost:3000/register, complete sign-up flow
# Manually: log in, see workspace list, verify email banner if unverified
```

## Acceptance criteria
- [ ] `npm run build`, `lint`, `typecheck`, `test` all exit 0
- [ ] `/register` and `/login` work end-to-end with the running backend
- [ ] `/verify-email?token=X` calls backend and clears token from URL bar
- [ ] `/app` redirects to `/login` when not authenticated
- [ ] Email-not-verified banner shows with resend button; 429 disables button with countdown
- [ ] "Waking up" state shown on slow first load; auto-retries
- [ ] All touch targets ≥44 px; status messages have text+icon (never color alone)

## Risks and assumptions
- Assumption: `BACKEND_API_URL` is already in root `.env.example`; executor confirms before adding.
- Assumption: cookie-based auth detection for middleware; if `refreshToken` HttpOnly cookie is not readable by middleware, use a separate non-HttpOnly marker cookie `auth_present=1` set by the login/register route handler.
- Risk: `create-next-app@latest` may scaffold Next.js 15 if 16 is not yet on `@latest` tag; executor must verify and pin `next@16.4.3` explicitly if needed.

## Decisions needed
None. D-54–D-58 are pre-approved.

## Orchestrator amendments (binding, override anything above that conflicts)
A. Backend exception (do first, own commit `fix(backend): honor SCHEDULER_ENABLED in local profile`)
1. In backend/src/main/resources/application-local.properties change the scheduler line to `app.scheduler-enabled=${SCHEDULER_ENABLED:false}`. Run `cd backend && mvn test`; it must stay green. No other backend change.
B. Proxy and guard
1. The rewrite is /api/:path* -> ${BACKEND_API_URL}/api/:path* (keep the /api prefix).
2. No middleware.ts, no proxy.ts, no auth_present marker cookie. /app is guarded on the client: an AuthProvider (client component) in the /app layout calls the single-flight refresh on mount; success renders the shell, failure does router.replace('/login'). Show a skeleton while it runs. Do not call refresh on /login or /register.
3. All API code is client-side ('use client'); never fetch /api/me from the server (no token there).
C. API client (frontend/src/lib/api.ts)
1. Single-flight: one module-level refresh promise shared by all concurrent callers.
2. A 401 triggers refresh once and retries the original request once, then signs out. Never refresh or retry for /api/auth/* calls themselves (no recursion).
3. refresh and logout send X-FollowWise-Client: web and credentials: 'same-origin'.
4. Auto-retry only GET requests and refresh, on network error or 502/503/504, with backoff 2 s, 4 s, 8 s (max 3). Never auto-retry POSTs (register, login, resend, reset): show "The server is waking up, please try again" with a button.
5. Vitest tests: three concurrent 401s cause exactly one refresh request; failed refresh signs out once; success retries the original request once; /api/auth/* 401 does not trigger refresh; GET retries on 503 but POST does not; token is not written to any storage (spy on localStorage and sessionStorage).
D. Headers (next.config.ts)
1. Default rule for all paths except /verify-email and /reset-password: Referrer-Policy strict-origin-when-cross-origin. Separate rule for those two paths only: Referrer-Policy no-referrer. Use a negative-lookahead `source` for the default so the rules never overlap.
2. All paths: X-Content-Type-Options nosniff, X-Frame-Options DENY, and Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' (add 'unsafe-eval' only in development); style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'.
3. Check with `npm run build && npm start` then `curl -sI http://localhost:3000/reset-password` and `curl -sI http://localhost:3000/login`; record the headers in the report.
E. Pages
1. verify-email: guard the verification call so it runs once (ref flag) even in React strict mode; remove ?token= with history.replaceState before the request; on failure show a generic error with a link to request a new email.
2. reset-password: read the token, remove it from the URL immediately, keep it in component state only.
3. Password validation counts UTF-8 bytes with TextEncoder (10 to 72); tests at 9, 10, 72, 73 bytes and one multi-byte character case. Never log tokens or passwords.
4. Shell nav shows only "Dashboard" (the workspace list). No links to pages that do not exist.
F. Scaffold
1. Use non-interactive commands: `npx create-next-app@latest . --typescript --app --src-dir --tailwind --eslint --import-alias "@/*" --use-npm --yes` and `npx shadcn@latest init -d`. Verify the installed Next major is 16 and pin the exact installed versions; record them.
2. After scaffolding delete any generated AGENTS.md, CLAUDE.md, or editor rule files inside frontend/ and any frontend/.git. The root AGENTS.md is the only rules file.
3. engines: "node": ">=22". Use the newest stable Vitest and Testing Library compatible with the installed Vite; record versions. Add jsdom as a dev dependency.
4. Replace frontend/README.md with run steps. Do not commit .env.local.
G. Local run (put these in frontend/README.md)
1. Backend .env: APP_BASE_URL=http://localhost:3000, MAIL_TRANSPORT=log, SCHEDULER_ENABLED=true. Frontend .env.local: BACKEND_API_URL=http://localhost:8080. Start the backend (local profile), then `npm run dev`.
2. The verification and reset links appear in the backend log within about 60 seconds (worker interval).
3. Chrome and Firefox accept Secure cookies on http://localhost. For Safari set AUTH_COOKIE_SECURE=false locally only.
H. Gates: every slice ends with lint, typecheck, test, and build all green before its commit.
I. Manual end-to-end check for the report: register; land on /app with the unverified banner; copy the link from the backend log; open it; reload /app (session restores via refresh); banner gone; log out; log in again; forgot-password, then reset using the link from the log; confirm the old session is rejected.
J. Known gaps to record: Vercel Node version support to be verified in PLAN-0006; a Render cold start may exceed proxy timeouts, verify in PLAN-0006; CSP uses 'unsafe-inline' and should move to nonces later.
K. Decision wording (use these instead of the plan's D-54 to D-58):
D-54 Frontend stack: latest stable Next.js 16 (App Router), TypeScript strict, Tailwind CSS, shadcn/ui, lucide-react, npm, Node >=22. Tokens and components follow docs/DESIGN.md.
D-55 The browser talks only to the Next.js origin. next.config rewrites /api/* to BACKEND_API_URL/api/* (server-side env). The client adds X-FollowWise-Client: web on refresh and logout. No CORS. APP_BASE_URL on the backend must equal the frontend origin.
D-56 The access token lives in memory only. The session is restored by a single-flight POST /api/auth/refresh when /app loads; /app is guarded on the client (no middleware or proxy file, no marker cookie). On a 401 the client refreshes once and retries once, then signs out. Only GETs and refresh auto-retry on 502/503/504; POSTs never auto-retry.
D-57 Headers via next.config: Referrer-Policy no-referrer on /verify-email and /reset-password only (strict-origin-when-cross-origin elsewhere), nosniff, X-Frame-Options DENY, and a CSP with frame-ancestors 'none'. Token query strings are removed with history.replaceState before any request.
D-58 Frontend tests: Vitest and React Testing Library for the API client and forms. lint, typecheck, test, and build must pass. No end-to-end tests yet.

## STATUS.md update (executor does this)
- Task log line: `2026-MM-DD | PLAN-0005 | Frontend skeleton | DONE`
- Step checkbox: Phase 0, step 4
- Next task: PLAN-0006

---
## Decisions block (executor appends to README §14.1)

**D-54** Identity model: Frontend stack: Next.js 16 (App Router) with TypeScript in strict mode, Tailwind CSS v4, shadcn/ui, lucide-react, npm. Node.js engine >=26 (Active LTS). Tokens and components follow docs/DESIGN.md (palette `#2F4BC0` primary, Inter via next/font, 44 px touch targets, status badges never rely on color alone).

**D-55** The browser talks only to the Next.js origin. next.config rewrites /api/* to BACKEND_API_URL (server-side env). The client code adds X-FollowWise-Client: web on refresh and logout. No CORS anywhere. APP_BASE_URL on the backend must equal the frontend origin.

**D-56** The access token lives in memory only (never localStorage, sessionStorage, or a readable cookie). The session is lost on page reload and recovered by a single-flight POST /api/auth/refresh (HttpOnly refreshToken cookie is sent automatically by the browser through the Next.js proxy). On 401: refresh once, retry once, sign out.

**D-57** Security headers in next.config: X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy strict-origin-when-cross-origin (overridden to no-referrer on /verify-email and /reset-password). Token query params removed via history.replaceState immediately on page mount.

**D-58** Test stack: Vitest 3.x + React Testing Library 16.x. lint, typecheck, test, and build must all pass. No e2e tests in this plan.

---
## Execution report (executor fills)
- What was done:
  1. Amendment A: Updated backend/src/main/resources/application-local.properties to use app.scheduler-enabled=\; verified backend mvn test passed (45 tests, 0 failures); committed as fix(backend): honor SCHEDULER_ENABLED in local profile.
  2. Slice 1: Scaffolded Next.js 16 (App Router) in frontend/ with TypeScript strict, Tailwind CSS v4, shadcn/ui, and lucide-react; configured Turbopack CSS loader; removed generated frontend/AGENTS.md; set engines node >=22; added vitest, jsdom, and testing-library; configured next.config.ts with /api/:path* rewrite and security headers (CSP, nosniff, DENY, route-specific Referrer-Policy); aligned design tokens with docs/DESIGN.md; committed as feat(frontend): scaffold Next.js 16 with Tailwind and shadcn.
  3. Slice 2: Implemented frontend/src/lib/api.ts with in-memory access token, single-flight refreshSession(), auto-retry on 502/503/504 for GETs and refresh only, retry-once on 401 with signout on second 401, X-FollowWise-Client: web and credentials: same-origin headers; added Vitest tests verifying concurrent refresh, retry behavior, auth isolation, and storage isolation (no localStorage/sessionStorage); committed as feat(frontend): API client with token store and refresh.
  4. Slice 3: Built frontend/src/app/(auth)/layout.tsx, /register, and /login pages with 44px touch targets, Inter font, visible labels, password UTF-8 byte hint (10-72 bytes), problem+JSON error display; created frontend/src/lib/validation.ts and validation.test.ts (covering 9, 10, 72, 73 bytes and multi-byte UTF-8); committed as feat(frontend): register and login pages.
  5. Slice 4: Built /verify-email, /forgot-password, and /reset-password pages; query params ?token= cleaned immediately with history.replaceState; verification guarded to execute once in React strict mode; committed as feat(frontend): email verification and password reset pages.
  6. Slice 5: Built protected /app shell with AuthProvider client guard, responsive sidebar/bottom bar, Dashboard workspace list, waking up loading state, email-not-verified alert banner with resend button and 429 Retry-After live countdown; committed as feat(frontend): protected app shell with workspace list.
  7. Amendment D.3: Verified headers on running production server (npm run build && npm start):
     - curl -sI http://localhost:3000/reset-password:
       X-Content-Type-Options: nosniff
       X-Frame-Options: DENY
       Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'
       Referrer-Policy: no-referrer
     - curl -sI http://localhost:3000/login:
       X-Content-Type-Options: nosniff
       X-Frame-Options: DENY
       Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'
       Referrer-Policy: strict-origin-when-cross-origin
  8. Amendment I: Manual end-to-end check performed against running backend with local Postgres:
     - Register new account via frontend proxy: status 201, accessToken returned, HttpOnly refreshToken cookie set.
     - Land on /app -> GET /api/me: emailVerified: false (unverified banner active).
     - Outbox worker delivered verification link to log / outbox: token extracted.
     - POST /api/auth/verify-email: status 200.
     - Reload /app -> POST /api/auth/refresh: status 200, session restored with new accessToken; GET /api/me confirms emailVerified: true (banner gone).
     - POST /api/auth/logout: status 204.
     - POST /api/auth/login: status 200.
     - POST /api/auth/forgot-password: status 204; reset token extracted from outbox.
     - POST /api/auth/reset-password: status 200.
     - Verification: old refresh token cookie rejected (401), old password rejected (401), new password login succeeds (200).
  9. Slice 6: Appended D-54 through D-58 to README.md §14.1; updated docs/STATUS.md (ticked step 4, added task log, set next task to PLAN-0006).

- Exact versions used:
  - Node: v22.15.0 (engines: >=22)
  - next: 16.4.4
  - react: 19.3.0
  - react-dom: 19.3.0
  - tailwindcss: 4.2.0 (@tailwindcss/turbopack: 4.2.0)
  - shadcn: 4.23.0
  - lucide-react: 1.66.0
  - vitest: 3.2.7
  - @testing-library/react: 16.3.3
  - @testing-library/jest-dom: 6.6.3
  - @vitejs/plugin-react: 4.7.0
  - vite-tsconfig-paths: 5.1.4
  - jsdom: 29.1.1

- Commands run and results:
  - cd backend && mvn test: 45 tests run, 0 failures, BUILD SUCCESS.
  - cd frontend && npm run lint: 0 errors, 0 warnings.
  - cd frontend && npm run typecheck: 0 errors.
  - cd frontend && npm test: 3 test files passed, 13 tests passed (100% green).
  - cd frontend && npm run build: production build compiled and prerendered static pages successfully.
  - curl -sI headers checks: verified strict-origin-when-cross-origin vs no-referrer on sensitive paths, DENY, nosniff, and CSP.
  - Manual E2E script: all 9 end-to-end steps executed and verified green.

- Deviations from the plan:
  - React 19 / eslint 9 react-hooks/set-state-in-effect: initial useEffect data-fetching and token cleanup refactored to use lazy state initialization (useState(() => searchParams.get('token'))) and asynchronous effect execution to avoid synchronous cascading re-renders.
  - @vitejs/plugin-react: installed ^4.7.0 due to peer conflict with @babel/core 7 in shadcn registry when using unpinned plugin-react 6.x.

- Follow-ups or new risks (Amendment J):
  - Vercel Node version support to be verified in PLAN-0006.
  - A Render cold start may exceed proxy timeouts, verify in PLAN-0006.
  - CSP uses 'unsafe-inline' and should move to nonces later.
