# PLAN-0013: Document workflow (approve, reject, due date, cancel, purge, filters)

Status: DRAFT        <!-- DRAFT -> APPROVED (founder) -> IN PROGRESS -> DONE | BLOCKED -->
Phase / step: Phase 1, step 1e-ii
Author: orchestrator   Executor: (model that executed)
Branch: plan-0013 (created by the founder; AI models run no git). Starts only after PLAN-0012 is merged.

## Goal
Finish the document module defined by D-94 to D-100 (already in the README after PLAN-0012): approve, reject, change due date, cancel, purge old versions, optimistic locking, and the derived overdue/expired/expiring list filters. No new decisions; follow D-94 to D-100 exactly. No reminders, frontend, or portal.

## Read first
README decision rows D-94 to D-100; document/DocumentService.java, DocumentRequestStore.java, DocumentStore.java, DocumentController.java, DocumentExceptionHandler.java, DocumentPayloadParser.java; ledger/LedgerService.java (lock order and conditional update pattern); app/BodySizeFilter.java; the existing DocumentApiTest (for fixtures).

## Out of scope
Reminders, notifications, frontend, portal, antivirus, retention jobs, orphan sweeper, new endpoints beyond D-98.

## Steps (each ends with `cd backend && mvn test` green; the founder commits, you do not)
### Slice 1: state transitions
POST .../approve {expectedVersion, expiresOn?}, .../reject {expectedVersion, reason}, .../due-date {expectedVersion, dueDate}, .../cancel {expectedVersion} per D-97 and D-98. Permissions: approve, reject, due-date need EDIT_DOCUMENTS; cancel needs MANAGE_DOCUMENTS (OWNER). Lock order app, party, request. Each mutation is one conditional UPDATE on id, workspace_id, app_id, version and cancelled_at IS NULL, bumping version and updated_at/updated_by; zero rows gives 409 stale_version with the current version. Rules: approve only from RECEIVED (else 409 invalid_state); expires_on = body expiresOn (a real date, not before 2000-01-01, not more than 20 years after today in the app zone) else today plus expiry_days else null; reject only from RECEIVED with reason 1 to 200 characters, no control characters or angle brackets (stored in status_reason); due date a real date within 5 years before and after today in the app zone, or null to clear; cancel allowed from any non-cancelled state and sets cancelled_at (cancelled requests accept no further writes: 409 request_cancelled; a cancelled request frees its unique slot per D-94). Archived app 409 app_archived; archived party 409 party_archived for all mutations. Bodies use the strict parser (8 KB); unknown keys rejected.
### Slice 2: purge
POST .../documents/{docId}/purge (MANAGE_DOCUMENTS). Only non-current, non-purged versions (current version 409 current_document; already purged 409 already_purged). In one transaction set purged_at and clear storage_key (the only update the trigger allows), then delete the object best effort after commit; a failed storage delete is logged with counts only and never reverts the purge.
### Slice 3: derived filters
Extend GET .../document-requests with status, overdue, expiringWithinDays (0 to 365), partyId, page, size (default 20, maximum 50), ordered by due_date nulls last then id, exactly per D-97 definitions, with "today" from the Clock in the app time zone: overdue = required AND status in (PENDING, REJECTED) AND due_date before today; expired = APPROVED AND expires_on before today (status=expired); expiring = APPROVED AND expires_on from today through today plus N. A request due today is not overdue; expiring on today is expiring, not expired. Invalid parameter values give a fixed-text 400. Cancelled requests are excluded unless status=cancelled. Responses include derived booleans overdue and expired.
### Slice 4: tests and docs
Tests (Testcontainers, fake storage, mutable Clock): every valid and invalid transition (approve a PENDING, approve twice, reject an APPROVED, cancel then approve, upload after cancel); the three expires_on rules and their boundaries; reject reason rules; due date limits and clearing; stale expectedVersion gives 409 with the current version for each action; two concurrent approvals give exactly one success; concurrent approve and reject give exactly one success; cancel frees the unique slot (assign again creates a new request); purge of the current version 409, of an old version succeeds, the stored object is gone, the row remains, download then returns 410, a second purge 409; a failing storage delete does not revert the purge; the filters with boundaries (due today, due yesterday, expires today, expires yesterday, expiringWithinDays 0 and 365, 366 rejected) in two app time zones across the date line; pagination, ordering, nulls last; permission matrix on every new endpoint (OWNER all; STAFF may approve, reject and change due date but gets 403 on cancel and purge; PORTAL_USER 403; non-member 404); id scoping is 404 for requests and documents of another app, party or workspace; archived app and archived party rules; SECRET_MARKER_123 in reason, dueDate and expiresOn never appears in any error or log; no stack traces; direct SQL still cannot update documents except the purge. Update docs/STATUS.md: tick "1e-ii. Document workflow (PLAN-0013)", add the log line | YYYY-MM-DD | PLAN-0013: document workflow (Phase 1, step 1e-ii) | <model> | Done |, set Next task to "PLAN-0014: reminder engine (Phase 1, step 1f)".

## Acceptance
- [ ] Every test above exists and the suite is green (report existing + new counts).
- [ ] No git commands were run; no decision text was changed.

---
## Execution report (executor fills)
- What was done:
- Files changed (real paths, grouped by slice):
- Deviations (list every one):
- Final test counts (existing + new):
