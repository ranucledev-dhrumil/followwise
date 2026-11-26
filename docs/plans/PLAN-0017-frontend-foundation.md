# PLAN-0017: Frontend foundation — typed API layer, app list, app shell and dashboard

Status: APPROVED        <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 2, step 2a
Author: Claude Sonnet 4.6   Executor: Gemini (non-Claude model)
Branch: plan-0017 (created by the founder; AI models run no git)

## Goal
Build the typed API layer (D-117), routes and navigation (D-118), UI state primitives and error mapping (D-119), template picker and create flow (D-120), and app home dashboard renderer (D-121). No people, ledger, document or reminder screens.

## VERIFY results
1. **openapi-typescript**: current stable version is **7.x** (e.g. 7.13.0+); supports OpenAPI 3.1 (produced by springdoc 3.1.1) and runs on Node ≥ 20, fully compatible with Node 22. CLI: `npx openapi-typescript <input> -o <output>`. Source: https://openapi-ts.dev / https://www.npmjs.com/package/openapi-typescript
2. **Backend OpenAPI export via MockMvc**: use `@SpringBootTest` + `@AutoConfigureMockMvc` with the `local` Spring profile (SecurityConfig permits `/v3/api-docs/**` only in that profile). GET `/v3/api-docs`, write response string to `docs/api/openapi.json`. For deterministic ordering add an `OpenApiSortConfig` bean (test-scoped or conditional on profile) that uses `TreeMap` for paths and components schemas. No timestamps in the document; springdoc does not emit them by default. Source: springdoc-openapi docs, medium.com/stackoverflow.com
3. **`Intl.supportedValuesOf('timeZone')`**: natively available in Node 22 and all modern browsers (Chrome 99+, Firefox 110+, Safari 15.4+). Known quirk: omits `UTC` and `Etc/*` zones. Fallback: wrap in `try/catch`; if unavailable return `['UTC', 'America/New_York', 'Europe/London', 'Asia/Kolkata']`. Validate an arbitrary string with `Intl.DateTimeFormat(undefined, { timeZone: s })` (throws on invalid). Source: MDN, stackoverflow.com

## Out of scope
- People, charges, ledger, documents, reminders screens
- Portal, staff invitations, billing, analytics, third-party scripts
- New environment variables or changes to `.env.example`
- Changes to the CSP (`next.config.ts`)
- Backend API changes beyond the OpenAPI export bean and sort config

## Inputs
- README sections 8.9, 9, 16; decision rows D-55 to D-58, D-62 to D-73, D-111 to D-116, D-117 to D-121
- `docs/DESIGN.md`
- `docs/plans/decisions/PLAN-0017-decisions.md` (append its five rows to README 14.1 verbatim)
- `frontend/src/lib/api.ts`, `frontend/src/app/app/auth-provider.tsx`, `frontend/src/app/app/layout.tsx`, `frontend/src/app/app/page.tsx`
- `backend/src/main/java/com/followwise/app/{AppController,TemplateController,FormsResponse,DashboardResponse,DashboardController,AppExceptionHandler}.java`
- `backend/src/main/java/com/followwise/security/SecurityConfig.java`
- `backend/src/main/resources/application*.properties`
- `backend/src/test/java/com/followwise/AbstractIntegrationTest.java`

## Dependency to add (dev only)
`openapi-typescript@^7.0.0` — https://www.npmjs.com/package/openapi-typescript
Install: `cd frontend && npm install -D openapi-typescript@^7.0.0`
No runtime dependency. No other new package allowed.

## Files to create or change
| Path | Action | Purpose |
|---|---|---|
| `backend/src/main/java/com/followwise/app/OpenApiSortConfig.java` | create | `OpenAPICustomiser` bean (local+test profiles only) sorting paths and schemas with `TreeMap` for deterministic output |
| `backend/src/test/java/com/followwise/app/OpenApiExportTest.java` | create | Writes `/v3/api-docs` to `docs/api/openapi.json`; asserts every frontend-called path is present |
| `backend/src/test/java/com/followwise/app/OpenApiDriftTest.java` | create | Copies committed file to temp, generates fresh doc, fails with a clear regen message if they differ |
| `docs/api/openapi.json` | create (generated) | Committed OpenAPI document; never edited by hand |
| `frontend/package.json` | change | Add `openapi-typescript@^7.0.0` to devDependencies; add `"api:types": "openapi-typescript ../docs/api/openapi.json -o src/lib/api/schema.ts"` script |
| `frontend/src/lib/api/schema.ts` | create (generated) | Generated types; never edited by hand; committed |
| `frontend/src/lib/api/templates.ts` | create | Typed wrapper: `listTemplates()`, `getTemplate(key)` |
| `frontend/src/lib/api/apps.ts` | create | Typed wrappers: `listApps(wsId)`, `createApp(wsId, body)`, `getApp(wsId, appId)` |
| `frontend/src/lib/api/forms.ts` | create | Typed wrapper: `getForms(wsId, appId)` |
| `frontend/src/lib/api/dashboard.ts` | create | Typed wrapper: `getDashboard(wsId, appId, month?)` |
| `frontend/src/lib/api/errors.ts` | create | `mapApiError(res, body)` → `AppError`; fixed friendly messages per D-119 |
| `frontend/src/lib/api/index.ts` | create | Re-exports all wrappers and `AppError` |
| `frontend/src/lib/format.ts` | create | `formatMoney(cents, currency)` and `parseIsoDate(s)` helpers |
| `frontend/src/lib/timezone.ts` | create | `getSupportedTimeZones()` and `isValidTimeZone(s)` |
| `frontend/src/components/ui/skeleton.tsx` | create | Pulse skeleton block (DESIGN.md tokens) |
| `frontend/src/components/shared/EmptyState.tsx` | create | Empty state: icon, sentence, one primary action |
| `frontend/src/components/shared/ErrorState.tsx` | create | Error state: message, retry button |
| `frontend/src/app/app/page.tsx` | change | Replace workspace card dump with app list (loading/empty/error/success), workspace switcher (only when >1 workspace), link to `/app/new` |
| `frontend/src/app/app/new/page.tsx` | create | Template picker + create form (name, timezone, POST, 409 limit_exceeded friendly message) |
| `frontend/src/app/app/apps/[appId]/layout.tsx` | create | App shell: app name, app switcher, nav items from forms response (only implemented screens), sign-out |
| `frontend/src/app/app/apps/[appId]/page.tsx` | create | App home: dashboard renderer (all ten widget types + UNAVAILABLE + unknown, month selector ±24 months, generatedAt, dashboard_timeout retry) |
| `frontend/src/app/app/layout.tsx` | change | Minimal outer shell (keeps AuthProvider); inner shell moves to `[appId]/layout.tsx` |
| `frontend/src/lib/api/errors.test.ts` | create | Error mapper tests (all statuses per D-119) |
| `frontend/src/lib/api/wrappers.test.ts` | create | Wrapper tests: method, path, body, typed return |
| `frontend/src/lib/format.test.ts` | create | Money and date helper tests |
| `frontend/src/lib/timezone.test.ts` | create | Timezone helper tests |
| `frontend/src/app/app/__tests__/AppList.test.tsx` | create | App list loading/empty/error/success + workspace switcher visibility |
| `frontend/src/app/app/__tests__/AppNew.test.tsx` | create | Template picker and create form tests |
| `frontend/src/app/app/__tests__/AppShell.test.tsx` | create | Nav labels from forms, no dead links, redirect on session failure |
| `frontend/src/app/app/__tests__/Dashboard.test.tsx` | create | All widget types, UNAVAILABLE, unknown type, month selector range, timeout retry, generatedAt |
| `docs/api/` | create dir | Holds committed `openapi.json` |
| `README.md` | change | Append D-117 to D-121 to section 14.1 exactly as written in `docs/plans/decisions/PLAN-0017-decisions.md` |
| `docs/STATUS.md` | change | Phase 2 steps checklist, phase tracker update, task log line, Next task |

## Steps

### Slice 0 — Backend: OpenAPI export and drift detection
1. Add `backend/src/main/java/com/followwise/app/OpenApiSortConfig.java`: annotate `@Configuration @Profile({"local","test"})`, register one `OpenAPICustomiser` bean that sorts `openApi.getPaths()` and `openApi.getComponents().getSchemas()` with `TreeMap` (null-safe).
2. Create `backend/src/test/java/com/followwise/app/OpenApiExportTest.java`: extend `AbstractIntegrationTest`, add `@AutoConfigureMockMvc`, `@ActiveProfiles("local")`; in `@Test void exportOpenApi()` GET `/v3/api-docs`, assert 200, write `ContentAsString` to `Paths.get("../docs/api/openapi.json")` (create parent dirs). Assert the document contains the paths: `/api/templates`, `/api/workspaces/{workspaceId}/apps`, `/api/workspaces/{workspaceId}/apps/{appId}`, `/api/workspaces/{workspaceId}/apps/{appId}/forms`, `/api/workspaces/{workspaceId}/apps/{appId}/dashboard`.
3. Create `backend/src/test/java/com/followwise/app/OpenApiDriftTest.java`: same profile setup; generate fresh doc to a `Files.createTempFile`; read committed `docs/api/openapi.json`; `assertEquals(committed, fresh, "OpenAPI document is out of date. Run: cd backend && mvn test -Dtest=OpenApiExportTest -pl . && commit docs/api/openapi.json")`.
4. Run `cd backend && mvn test -Dtest=OpenApiExportTest -pl . -q` to generate `docs/api/openapi.json`. Commit the generated file.

**Check**: `mvn test -Dtest=OpenApiDriftTest` exits 0. Manually edit `docs/api/openapi.json`, re-run — it must fail with the regen message.

### Slice 1 — Frontend: install openapi-typescript and generate schema
5. `cd frontend && npm install -D openapi-typescript@^7.0.0`
6. Add to `frontend/package.json` scripts: `"api:types": "openapi-typescript ../docs/api/openapi.json -o src/lib/api/schema.ts"`
7. `npm run api:types` — generates `frontend/src/lib/api/schema.ts`. Commit it.

**Check**: `npm run api:types` leaves no uncommitted diff (run twice; second run produces no change).

### Slice 2 — Error mapper and format helpers
8. Create `frontend/src/lib/api/errors.ts`: export `AppError { message: string; retryAfterSeconds?: number }` and `mapApiError(status, body, retryAfterHeader?)`: 401 → handled by client (never reached here); 403 → "You don't have access to this resource."; 404 → "Not found."; 409 with `body.type === 'limit_exceeded'` → "You have reached the maximum number of apps for this workspace."; other 409 and 422 → `body.detail` if it matches the fixed server strings, else generic; 429 → "Too many requests. Please wait {N} seconds." (parse `Retry-After` header); 503 with `body.detail === 'dashboard_timeout'` → "Dashboard is taking too long. Try again."; other 503 and network errors → "The server is waking up. Please try again."; unknown → "Something went wrong. Please try again." Never interpolate raw server text outside the explicit allow-list above.
9. Create `frontend/src/lib/format.ts`: `formatMoney(cents: number, currency: string): string` using `Intl.NumberFormat` with `style:'currency'`; `parseIsoDate(s: string): { year: number; month: number; day: number }` splitting on `-` without constructing a `Date` object (no TZ shift).
10. Create `frontend/src/lib/timezone.ts`: `getSupportedTimeZones(): string[]` — try `Intl.supportedValuesOf('timeZone')`, catch → return fallback `['UTC','America/New_York','Europe/London','Asia/Tokyo','Asia/Kolkata','Australia/Sydney']`; `isValidTimeZone(s: string): boolean` — try `new Intl.DateTimeFormat(undefined, { timeZone: s })`, return true; catch → false.
11. Write tests: `errors.test.ts` (all statuses in D-119; no raw server text; unknown status); `format.test.ts` (money at 0, 1, 99, 100, 123456, 100000000000 cents; date parse does not shift under `TZ=UTC` and `TZ=Asia/Kolkata`); `timezone.test.ts` (fallback when `Intl.supportedValuesOf` throws; `isValidTimeZone` accepts `America/New_York`, rejects `Fake/Zone`).

**Check**: `npm run test -- --reporter=verbose` all pass; no console errors.

### Slice 3 — Typed API wrappers
12. Create `frontend/src/lib/api/templates.ts`, `apps.ts`, `forms.ts`, `dashboard.ts`. Each wrapper calls `apiFetch`, checks `res.ok`, throws `mapApiError(res.status, await res.json(), ...)` on error, returns typed data cast from the generated schema types.
13. Create `frontend/src/lib/api/index.ts` re-exporting all wrappers and `AppError`.
14. Write `wrappers.test.ts`: mock `apiFetch`; for each wrapper assert correct HTTP method, path template with IDs substituted, request body where applicable, and that the return value is the typed JSON. Test that a 404 response throws with the fixed 404 message and that a 409 `limit_exceeded` throws with the app-limit message.

**Check**: `npm run test` green; `npm run typecheck` exits 0.

### Slice 4 — Shared UI components
15. Create `frontend/src/components/ui/skeleton.tsx`: single `<div>` with `animate-pulse bg-[#E2E5EA] rounded` and configurable `className`; use DESIGN.md surface tokens.
16. Create `frontend/src/components/shared/EmptyState.tsx`: accepts `icon`, `message`, `action` (label + `onClick`); renders centred card per DESIGN.md. Never relies on colour alone for state.
17. Create `frontend/src/components/shared/ErrorState.tsx`: accepts `message` and `onRetry`; renders error card with retry button.

**Check**: each component renders in jsdom without error (add minimal render tests in the slice 4 test file or inline in AppList tests).

### Slice 5 — App list page and workspace switcher (`/app`)
18. Replace `frontend/src/app/app/page.tsx`: on mount call `apiFetch('/api/me')` and `listApps(workspaceId)`; show Skeleton while loading; show EmptyState ("No apps yet. Create your first app.", link to `/app/new`) when list is empty; show ErrorState with retry on error. On success render app cards (name, template label). Include a workspace switcher `<select>` that appears only when `GET /api/me` returns more than one workspace; switching re-fetches the app list for the selected workspace. Redirect to `/login` via the existing `signOutHandler` when session cannot be restored (already handled by `auth-provider.tsx`; no extra logic needed).
19. Update `frontend/src/app/app/layout.tsx` to keep only `<AuthProvider>` wrapping `{children}`; remove the hard-coded sidebar and mobile nav (they move to `[appId]/layout.tsx`).
20. Write `AppList.test.tsx`: loading state renders skeletons; empty state shows the "Create your first app" action; error state shows ErrorState with retry; success state renders app names; workspace switcher present only when >1 workspace; no raw server text; no console errors.

**Check**: `npm run test -- AppList` green.

### Slice 6 — Template picker and create flow (`/app/new`)
21. Create `frontend/src/app/app/new/page.tsx`: call `listTemplates()`; render each template as a selectable card with key and the friendly description from D-120 (small constant object in the component keyed by `templateKey`); form fields: name (1–100 chars, no control chars `\x00–\x1F` or `<>`), timezone (`<select>` populated from `getSupportedTimeZones()` with browser's `Intl.DateTimeFormat().resolvedOptions().timeZone` preselected; fallback to `'America/New_York'` if not in list; validate with `isValidTimeZone`). On submit: `createApp(workspaceId, { templateKey, name, timezone })`; success → `router.push('/app/apps/${appId}')`; 409 `limit_exceeded` → show friendly message from `mapApiError`; other errors → show ErrorState; never auto-retry on POST.
22. Write `AppNew.test.tsx`: shows exactly the templates returned; friendly descriptions match D-120; name field rejects empty, 101-char, control-char, angle-bracket values; name field accepts 1-char and 100-char; timezone preselected; unknown zone rejected by `isValidTimeZone`; 409 `limit_exceeded` shows the app-limit message; no auto-retry; no raw server text; no console errors.

**Check**: `npm run test -- AppNew` green.

### Slice 7 — App shell and nav (`/app/apps/[appId]/layout.tsx`)
23. Create `frontend/src/app/app/apps/[appId]/layout.tsx`: on mount fetch `getForms(workspaceId, appId)`. Build nav from `forms.partyTypes` (each type becomes a nav item labelled with its `label`; link to `/app/apps/${appId}/parties/${type.id}` — these routes don't exist yet, so render them conditionally: only link to routes that exist; in this plan no party routes exist so the nav items are present but rendered as non-linked text or disabled). Dashboard link always present. App name and an app switcher (`listApps`) in the header; sign-out button. Full loading skeleton while forms load; redirect to `/login` on session failure.
24. Write `AppShell.test.tsx`: nav label "Clients" appears when forms returns a party type with label "Clients"; nav contains no dead links (no `<a href>` or `<Link>` to unbuilt routes); redirect to `/login` when `refreshSession` throws; no raw server text; no console errors.

**Check**: `npm run test -- AppShell` green.

### Slice 8 — App home dashboard (`/app/apps/[appId]/page.tsx`)
25. Create `frontend/src/app/app/apps/[appId]/page.tsx`: call `getDashboard(workspaceId, appId, month)`; show Skeleton; on `dashboard_timeout` 503 show retry message per D-121; on other errors show ErrorState. On success render widgets by `type`:
   - `sum_expected_period`, `sum_collected_period` → SumWidget (formatted money, `formatMoney`)
   - `count_overdue_charges` → CountWidget
   - `list_overdue_parties`, `list_expiring_leases`, `list_missing_docs`, `list_expiring_docs` → ListWidget (rows with `totalCount`; no links since party screens do not exist yet)
   - `completion_pct` → CompletionWidget (percentage per party; overall pct)
   - Widget with `status === 'UNAVAILABLE'` → neutral card with `reason` in plain text
   - Unknown `type` → neutral card ("Widget unavailable")
   - Month selector: `<select>` with 24 months before and 24 months after current month (use `parseIsoDate` on `dashboard.month`); never offer months beyond ±24; show the month the server returned as selected.
   - Show `generatedAt` as "Updated: {relative time or formatted instant}".
   - No widget crash isolates to that widget only (use per-widget error boundary or try/catch render).
26. Write `Dashboard.test.tsx`: one test per widget type (10 types + UNAVAILABLE + unknown = 12 tests); month selector has exactly 49 options (24 before + current + 24 after); selecting a month calls `getDashboard` with the new month param; `dashboard_timeout` shows retry message; `generatedAt` is shown; unknown widget type renders neutral card; UNAVAILABLE shows reason; no raw server text; no console errors.

**Check**: `npm run test -- Dashboard` green.

### Slice 9 — Final checks and docs
27. `npm run lint && npm run typecheck && npm run test && npm run build` all green.
28. Append D-117 to D-121 to README section 14.1 exactly as written in `docs/plans/decisions/PLAN-0017-decisions.md`.
29. Update `docs/STATUS.md` per the STATUS.md update section below.

**Check**: build produces no TypeScript errors; lint clean; all tests pass; `npm run api:types` leaves no diff.

## Checks (how to prove it works)
- `cd backend && mvn test -Dtest=OpenApiDriftTest` exits 0
- Deliberately corrupt `docs/api/openapi.json` and re-run: test fails with the regen message
- `cd frontend && npm run api:types` twice: second run produces no diff
- `npm run test` green (all suites listed below pass)
- `npm run typecheck` exits 0
- `npm run lint` exits 0
- `npm run build` exits 0

### Required test list
| Suite | Key cases |
|---|---|
| `errors.test.ts` | 403, 404, 409 limit_exceeded, 409 other, 422, 429+Retry-After, 503 dashboard_timeout, 503 other, network failure, unknown status; none return raw server text |
| `wrappers.test.ts` | Each wrapper: correct method, path, body; 404 → fixed message; 409 limit_exceeded → app-limit message; typed return |
| `format.test.ts` | `formatMoney`: 0, 1, 99, 100, 123456, 100000000000 cents; `parseIsoDate` does not shift under `TZ=UTC` and `TZ=Asia/Kolkata` |
| `timezone.test.ts` | Fallback when `Intl.supportedValuesOf` throws; `isValidTimeZone('America/New_York')` true; `isValidTimeZone('Fake/Zone')` false |
| `AppList.test.tsx` | Loading/empty/error/success; workspace switcher only when >1 workspace; no raw server text; no console errors |
| `AppNew.test.tsx` | Shows returned templates with D-120 descriptions; name 1/100 char valid, empty/101/control char/angle bracket invalid; timezone preselected; unknown zone rejected; 409 limit_exceeded message; no auto-retry; no raw server text; no console errors |
| `AppShell.test.tsx` | Nav label "Clients" from forms; no dead links; redirect to /login on session failure; no raw server text; no console errors |
| `Dashboard.test.tsx` | 10 widget types + UNAVAILABLE + unknown (12 tests); month selector 49 options; month change calls getDashboard; dashboard_timeout retry; generatedAt shown; unknown type neutral card; no console errors |

## Acceptance criteria
- [ ] `mvn test -Dtest=OpenApiDriftTest` exits 0 on a clean checkout
- [ ] `npm run api:types` twice leaves no diff
- [ ] All 8 frontend test suites green with no console errors
- [ ] `npm run typecheck && npm run lint && npm run build` all exit 0
- [ ] `/app` lists apps and shows workspace switcher only with >1 workspace
- [ ] `/app/new` template picker matches D-120 descriptions; create succeeds and redirects to app home
- [ ] `/app/apps/[appId]` dashboard renders all widget types; month selector ±24 months; unknown widget does not crash
- [ ] No raw server text surfaces in any state
- [ ] No backend files outside `OpenApiSortConfig.java`, `OpenApiExportTest.java`, `OpenApiDriftTest.java` are changed

## Risks and assumptions
- `AbstractIntegrationTest` starts a Postgres 16 Testcontainer; both new backend tests extend it and therefore require Docker running.
- springdoc 3.1.1 emits OpenAPI 3.1; openapi-typescript 7.x reads 3.1 natively — confirmed by VERIFY.
- `Intl.supportedValuesOf` is available in jsdom (Node 22 under Vitest); if not, the timezone helper's try/catch fallback covers it.
- The app switcher in the app shell requires `listApps` per workspace; with 5 app limit and free tier this is at most one small call.
- Dashboard widget types in the response match exactly the ten types in `DashboardResponse.java` plus `UNAVAILABLE` status; no undocumented types are expected.

## Decisions needed
None.

## STATUS.md update (executor does this)
- Add Phase 2 steps checklist:
  - [ ] 2a. Frontend foundation (PLAN-0017)
  - [ ] 2b. People screens (PLAN-0018)
  - [ ] 2c. Charges and ledger screens (PLAN-0019)
  - [ ] 2d. Documents screens (PLAN-0020)
  - [ ] 2e. Reminders: per-app opt-in and screens (PLAN-0021)
  - [ ] 2f. Onboarding, polish, legal pages (PLAN-0022)
- Set Phase 2 tracker row Status to IN PROGRESS
- Tick [x] 2a when done
- Task log line: `| 2026-11-24 | PLAN-0017: frontend foundation (Phase 2, step 2a) | <model> | Done |`
- Set Next task to "PLAN-0018: people screens (Phase 2, step 2b)"

---
## Orchestrator amendments (binding, override anything above that conflicts)
A. No git commands; no downloads or installs except the single dev dependency openapi-typescript; never edit AGENTS.md. STATUS log lines use the actual date.
B. Routes follow the corrected D-118: /app redirects to /app/w/[workspaceId] (first workspace from GET /api/me); /app/w/[workspaceId] is the app list and workspace switcher (switching navigates to another /app/w/[id]); /app/w/[workspaceId]/new is the create flow; /app/w/[workspaceId]/apps/[appId] is the app shell and home. Replace every path in the Files table and the slices accordingly. A 404 from the API for the workspace or app shows a not-found state. Extra tests: /app redirects to the first workspace; switching workspace changes the URL; a workspaceId the API rejects shows not-found; the create link and the /new page are offered only when the role of that workspace in /api/me is OWNER (STAFF and PORTAL_USER see the list without a create action; a direct visit shows a no-access message).
C. Add frontend/src/lib/api/me.ts (typed GET /api/me) and use it from every page; no page calls apiFetch directly (check with grep). The pending state of every submit button disables it (no double submit).
D. Navigation: build items from the forms response, then filter through a registry of implemented screens (production value: only "dashboard"). No party, charge, or document items appear in this plan. Tests inject a registry containing a "people" screen to prove the label comes from the forms response (Clients, Tenants) and that with the production registry no such item and no dead link exists. Replace Slice 7 step 23.
E. Dashboard widgets, the real ten types: count_overdue_charges, sum_overdue_charges, count_overdue_docs, sum_expected_period, sum_collected_period, list_overdue_parties, list_expiring_leases, list_missing_docs, list_expiring_docs, completion_pct_by_party (the plan's "completion_pct" is wrong). Read DashboardResponse.java for the exact data shapes. Count widgets show data.count; sum widgets show formatMoney(data.cents, data.currency); list widgets show up to 20 rows and "Showing N of totalCount": overdue parties {displayName, overdueCents as money, oldestDueDate, overdueChargeCount}; leases {displayName, date, daysLeft}; missing docs {displayName, missingCount, oldestDueDate}; expiring docs {displayName, label, expiresOn, daysLeft}; completion {displayName, pct, approvedCount of requiredCount} plus overallPct. Titles come from the response label. The month selector is anchored on the month of the response field today (not the selected month) and offers exactly 49 options. Add formatDate(iso) (Date.UTC components with Intl.DateTimeFormat and timeZone UTC; never new Date on a date-only string) and test it under two process TZ values (set process.env.TZ in the test) plus month-end and leap-day inputs. Tests: the corrected twelve widget cases, and one test proving the selector range stays anchored on today after selecting +24.
F. Error mapper: server codes are in the problem+json type field (limit_exceeded, stale_version, app_archived, version_limit, dashboard_timeout, and so on). Read AppExceptionHandler and every other *ExceptionHandler to list the real type values and statuses; map by type first, then by status. 409 and 422 show the server detail after removing control characters and truncating to 200 characters (the backend guarantees fixed text); every other case uses the fixed messages of D-119; an unknown type or status gives the generic message. Tests cover each real type found.
G. Backend export and drift: (1) Try the springdoc property springdoc.writer-with-order-by-keys=true in the local and test properties first (verify it exists in springdoc 3.1); add a custom OpenApiCustomizer bean (class org.springdoc.core.customizers.OpenApiCustomizer; verify the name) only if the output is still unstable. (2) OpenApiExportTest writes docs/api/openapi.json ONLY when the system property openapi.export=true is set (cd backend && mvn test -Dtest=OpenApiExportTest -Dopenapi.export=true); without it, it asserts that every path the frontend wrappers call is present and writes nothing. (3) OpenApiDriftTest never writes; it compares the committed file to a fresh document through one small static method that returns a difference message; add tests: an equal pair passes, and a deliberately modified temporary copy fails with the regeneration message containing the exact command above. (4) The whole backend suite, run twice, leaves git status clean. (5) The committed document contains no host names, ports, or timestamps (report the result of grep -c -i -E "localhost|127.0.0.1" docs/api/openapi.json and explain any hit; a servers entry produced by MockMvc must be removed or normalized).
H. Required report items, all from real command output: sed (with line numbers) of the error mapper, the nav registry, the widget switch, the month selector anchor, and the export and drift tests; the real list of problem+json type values you found; the raw output of grep -rhoE "(it|test)\(['\"][^'\"]+" frontend/src (all test titles); backend mvn test three times and frontend lint, typecheck, test, build three times; the exact installed openapi-typescript version; the output of git-free checks: grep -rn "apiFetch" frontend/src/app (must show none outside tests), grep -rn "new Date(" frontend/src --include=*.ts --include=*.tsx (explain each non-test hit), grep -rn "completion_pct\b" frontend/src (none).
---
## Execution report (executor fills)
- What was done:
- Files changed (grouped by slice):
- Commands run and results (no git commands):
- Deviations from the plan:
- Follow-ups or new risks:

---
## Decision references
- D-117: Typed API layer from committed OpenAPI document
- D-118: Routes, navigation and workspace switcher
- D-119: UI states, error mapping, money and date rules
- D-120: Template picker, create form, friendly descriptions
- D-121: App home dashboard generic renderer
