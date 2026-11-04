<!-- Living source of truth for project knowledge. Progress lives in docs/STATUS.md. -->

# FollowWise

Powered by the Configurable Engine for Management: a configuration-driven platform that turns a short interview into a working management app

**Version:** 1.2 (build-ready baseline for v1)

**Date:** October 22, 2026

**Status:** Pre-build. Current progress is tracked in `docs/STATUS.md`.

**Audience:** The founder, and any AI coding assistant (for example Antigravity) that will help build the product. Section 14 is written specifically as standing context for AI tools.

**Product name:** FollowWise (confirmed). The shared core behind every FollowWise template is called the Configurable Engine for Management (CEM) in this document.

## Docs map and working agreement
| File | Purpose |
|---|---|
| `README.md` | This document. Project knowledge, architecture, decision log. |
| `AGENTS.md` | Rules for every AI model: roles, workflow, token-saving rules, engineering rules. |
| `docs/STATUS.md` | Single status log: current version, phase, task log, open decisions. |
| `docs/DESIGN.md` | UI/UX design system. |
| `docs/plans/` | One plan per task. Claude plans, a Gemini model executes. |

This chat is the orchestrator. Claude models in Antigravity plan only and never change code. Gemini models execute approved plans. See `AGENTS.md`.

## Version history

**Version 1.2 (this version):**

- Frontend hosting decided: Next.js on Vercel. The free Hobby plan is for non-commercial use only, so Pro is required before charging anyone (Section 8.7 and 8.8).

- Email stays on Gmail SMTP using the founder Gmail account at first. All email settings are environment variables so the sender can be switched at any time (Section 8.7).

- Confirmed: exactly two templates in v1, Rental Property Manager and Client Document Tracker.

- New: first-revenue cost plan (Section 8.8), frontend to backend connection (Section 8.9), and the build plan with next steps (Section 16).

- Open items closed or deliberately left open (Section 13.3). Decision log extended to D-30 (D-31 to D-40 added after v1.2 in this file).

**Version 1.1:** product named FollowWise; Render, Supabase, and Gmail chosen; staff roles, currency, and minimum template specifications settled. **Version 1.0:** initial brief.

## 1. Summary

FollowWise is a web platform that lets a non-technical person get a custom management app for their small business by answering a short set of questions. Examples are a rental property manager, a tutor or class manager, and a client document tracker for bookkeepers and consultants. Each app is used day to day to track people, money owed, documents, and deadlines, and it automatically reminds the people who are late.

The central technical decision is this:

> **AI generates configuration, never code.** The platform ships a fixed set of well-tested building blocks (people, records, recurring charges, ledger, documents, reminders, dashboards, forms). The AI interview only chooses and configures those blocks. Every app is therefore reliable, easy to change, and tested once at the block level instead of once per generated app.

The business model is a free tier for one small app, with paid plans for more apps, more people tracked, and extra features. The first market is the United States. The United Kingdom follows as version 2. The founder is currently working alone, with a zero-cost infrastructure budget.

## 2. The problem

Small businesses and independent professionals constantly wait on other people for something: rent, fees, signed forms, receipts, insurance certificates. Today they manage this with notebooks, spreadsheets, and ad-hoc messages. The consequences are predictable:

- They forget who has been chased and who has not.

- Money arrives late, or never.

- Documents or licences expire without anyone noticing.

- Hours every week go to manual follow-up.

- There is no clean record when a dispute or audit happens.

Existing tools fall into two camps. Vertical products (for example landlord software) are built for one niche. General no-code builders (Airtable, Glide, Zoho Creator, Notion) are flexible but expect the user to design their own system, which most small business owners will not do.

#### Core pattern

> Someone needs things from other people (documents, data, money), by a deadline, and those people tend to ignore them.

Every template FollowWise ships is an instance of this pattern. The two families are **collect-money-from-people** (ledger-based) and **collect-documents-from-people** (request and expiry based).

## 3. The solution

### 3.1 What the user experiences

1.  The owner signs up and either picks a template (version 1) or answers a short interview (later phase).

2.  The platform produces a working app: people list, forms, dashboard, reminder rules, all named for their business.

3.  The owner adds the people they track (tenants, students, clients).

4.  The system tracks what is due, received, overdue, or about to expire, and sends escalating email reminders automatically.

5.  The tracked person logs in to a simple portal to see what they owe or must provide, and to upload documents.

6.  The owner sees an automatically generated dashboard and a complete history of activity.

### 3.2 One account, many apps

A single customer can create several apps (for example a rental manager and a tutoring manager). This is both a natural upsell and a retention advantage, because the more of a person's routine runs on the platform, the less likely they are to leave.

### 3.3 What FollowWise is not

- It is not a general no-code builder. Customers never design tables or workflows from scratch.

- It is not a payment processor in version 1. It tracks payments but does not collect them.

- It is not a hospital, clinical, point-of-sale, or inventory system. These are poor fits for the engine.

- It does not let AI write or run code for a customer's app.

## 4. Guiding principles

| **Principle**                  | **What it means in practice**                                                                                                                                   |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Configuration, not code        | AI output is a validated configuration document (JSON) that references known block types. No generated code is ever executed.                                   |
| Templates first, AI second     | Hand-crafted templates ship first and prove the engine. The AI interview is layered on top later and produces the same configuration format that templates use. |
| Sell verticals, not the engine | Customers see "Rental Property Manager," not "a configurable engine." Each template gets its own name, landing page, and wording.                               |
| Daily use over setup           | Revenue depends on people using the product every week. Reminders, notifications, and a good phone experience matter more than the builder.                     |
| API-first                      | The backend is a standalone service. The web app and a future mobile app use the same APIs.                                                                     |
| Zero cost to start             | Every infrastructure choice must have a usable free tier. Spend only once revenue exists.                                                                       |
| Trust by default               | Documents and money records are sensitive. Encryption, permissions, backups, and an audit log are required from day one.                                        |
| Prove before expanding         | Launch with a small number of templates, get real paying users, then add more.                                                                                  |

## 5. Target market and launch templates

### 5.1 Market

**Version 1: United States.** Customers there are used to paying monthly for software, pay in dollars, and can be reached entirely online (Reddit communities, LinkedIn, Facebook groups, X, cold email). **Version 2: United Kingdom**, which adds GBP, UK-specific wording, and GDPR handling. India-focused ideas, including a Splitwise-style chat bot and templates such as PG or society management, are parked until the product has proven itself abroad.

### 5.2 Launch templates

| **Template**                  | **Audience**                             | **Why this one**                                                                                                                   |
|-------------------------------|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| Rental Property Manager       | Small landlords with 1 to 20 units       | Large market, monthly use, exercises the money half of the engine: recurring charges, ledger, late fees, reminders.                |
| Client Document Tracker       | Bookkeepers, small agencies, consultants | Highest willingness to pay. Exercises the document half: requests, expiry, reminders, client portal.                               |
| Tutor / Class Manager (third) | Independent tutors, small classes        | Same ledger logic as rentals with different wording. Added right after launch to prove that a new template takes days, not months. |

*Version 1 launches with the first two templates. The third is the first post-launch addition and serves as proof of the modularity claim.*

### 5.3 Where the engine fits and where it does not

- **Strong fit:** document collection from clients, expiry and compliance tracking, recurring payment tracking, trader and job-work receivables.

- **Partial fit (extra work needed):** property management beyond rent (maintenance, leases, deposits), appointment-based businesses (needs scheduling), accounting-adjacent products (needs integrations).

- **Poor fit (avoid on this engine):** hospital and clinical systems, inventory or point-of-sale, complex custom workflows and CRM pipelines, real-time collaboration tools.

## 6. The engine: building blocks

Every app is assembled from the following fixed blocks. Each block is implemented, tested, and secured once.

| **Block**        | **Purpose**                                                                         | **Example configuration**                                            |
|------------------|-------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| Party            | A person or organization being tracked, with custom fields.                         | Label "Tenant"; fields: name, email, phone, unit, lease start.       |
| Record           | A structured item attached to a party or to the app (for example a unit or a case). | Label "Unit"; fields: address, rent amount, status.                  |
| Charge schedule  | Recurring, variable, or one-time amounts owed.                                      | Monthly rent on the 1st; one-time late fee after day 5.              |
| Ledger           | Charges, payments, balances, and history. In v1, payments are recorded manually.    | Running balance per tenant.                                          |
| Document request | A checklist of items a party must provide, with optional expiry.                    | W-9, insurance certificate (expires yearly).                         |
| Document store   | Uploaded files with versions, approval status, and expiry date.                     | Approve or reject a submitted lease copy.                            |
| Reminder rule    | Escalating notifications tied to due dates or expiries.                             | Email 3 days before, on the day, 3 days after, then alert the owner. |
| Form             | Data entry screens generated from field definitions.                                | Add-tenant form, record-payment form.                                |
| Dashboard        | Auto-generated widgets driven by the other blocks.                                  | Total overdue, overdue by person, expiring in 30 days.               |
| Audit log        | Immutable record of who did what and when.                                          | Payment recorded by user X at time Y.                                |

### 6.1 The App Definition

An app is stored as a single versioned **App Definition**: a JSON document that lists which blocks the app uses and how each is configured. Templates are App Definitions written by hand. The AI interview (later phase) produces App Definitions in exactly the same format. Conceptually:

```json
{
  "name": "Maple Street Rentals",
  "template": "rental-property-manager",
  "parties": [{ "type": "tenant", "label": "Tenant", "fields": [ ... ] }],
  "records": [{ "type": "unit", "label": "Unit", "fields": [ ... ] }],
  "chargeSchedules": [{ "name": "Monthly rent", "frequency": "monthly", "dueDay": 1 }],
  "reminderRules": [{ "trigger": "charge.due", "offsetDays": -3, "channel": "email" }],
  "dashboard": { "widgets": ["totalOverdue", "overdueByParty", "collectedThisMonth"] }
}
```

}

Every App Definition is validated against a strict schema before it is saved. Anything that does not validate is rejected. This is what makes AI output safe.

### 6.2 Handling changes after launch

Owners will ask for changes such as "also track security deposits." Because apps are configuration, a change is a new version of the App Definition. Rules for safe changes:

- App Definitions are versioned. Old versions are kept so a change can be previewed and rolled back.

- Adding a field never breaks existing data. Custom field values are stored in a flexible JSONB column, so existing rows simply have an empty value.

- Removing or renaming a field hides or relabels it instead of deleting data. Data is only deleted by an explicit, confirmed action.

- Changes are applied by a migration step that checks the new definition against existing data first.

## 7. Version 1 scope

### 7.1 Included

- Web application, responsive so that it works well on phones.

- Standalone backend exposing REST APIs (reusable later by a mobile app).

- Owner accounts with email and password sign-up and secure login.

- Tracked-person accounts (tenants, clients) with a simple portal. See Section 9 for the access design.

- Exactly two launch templates: Rental Property Manager and Client Document Tracker. Tutor / Class Manager is the first addition after launch, not part of v1.

- The engine blocks listed in Section 6, as needed by those two templates.

- Manual payment tracking: the owner marks charges as paid. No money moves through the platform.

- Email reminders with escalation rules, sent through a dedicated Gmail account (Section 8.7).

- Document upload, approval or rejection, and expiry tracking.

- Auto-generated dashboard and activity history.

- Multiple apps per account.

- Role-based permission system (Owner, Staff, Portal user) built into every endpoint from the start.

- Free tier (one small app) and the structure for paid plans. Billing can be turned on once the first paying customers appear.

- Privacy policy, terms of service, and basic data export and deletion.

### 7.2 Explicitly excluded from v1

| **Excluded**                                                    | **Reason / when**                                                                                                                         |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| Collecting payments (Stripe or similar)                         | Adds fees, compliance, and risk. Planned after v1 once tracking is proven.                                                                |
| SMS and WhatsApp reminders                                      | Costs money per message. Planned as paid features after v1.                                                                               |
| Native mobile app                                               | Web first. Backend is already API-first so the app can reuse it.                                                                          |
| AI setup interview and AI chat-based edits                      | Templates first. Added in the next phase on top of the working engine.                                                                    |
| UK market, GBP, and GDPR specifics                              | Version 2.                                                                                                                                |
| Bank, QuickBooks, and calendar integrations                     | Later, driven by customer demand.                                                                                                         |
| E-signature                                                     | Later, mainly for the document tracker and lease use cases.                                                                               |
| Staff invitation and seat-management screens                    | The permission system exists from day one. The screens are added when the first team-based customer asks.                                 |
| Multi-currency in the interface                                 | A currency code is stored on every amount now. The UI shows USD only until the UK launch.                                                 |
| India-focused templates and the chat-bot expense splitter       | Parked until the product is proven abroad.                                                                                                |
| Collecting highly sensitive identifiers (for example full SSNs) | Not needed for v1 templates. Avoiding them greatly reduces risk. Owners are warned not to upload such data until stronger controls exist. |

### 7.3 Minimum template specifications

These are the smallest useful versions of the two launch templates. They are starting hypotheses to refine after the first user conversations. Reminder timings are defaults that an owner can change.

#### Rental Property Manager

| **Element**         | **Minimum v1 content**                                                                                                                                                                          |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tenant (Party)      | Required: name, email. Optional: phone. Lease: monthly rent, due day of month, lease start date, lease end date. Linked to one unit.                                                            |
| Unit (Record)       | Label or address (required), default rent amount, status (occupied or vacant).                                                                                                                  |
| Charges             | Monthly rent, generated automatically on the due day. Optional late fee: a flat amount applied after a grace period. Off by default because US late-fee rules vary by state; the owner sets it. |
| Payments            | Recorded manually by the owner: amount (full or partial), date, and a short method note.                                                                                                        |
| Reminders to tenant | 3 days before due, on the due date, 3 days after, and 7 days after (firmer wording).                                                                                                            |
| Reminders to owner  | Alert when a tenant is 7 days overdue. Alert 60 days before a lease ends.                                                                                                                       |
| Dashboard           | Expected this month, collected this month, total overdue, list of overdue tenants, leases ending within 60 days.                                                                                |
| Not in v1           | Security deposits, maintenance requests, receipts, utility splitting.                                                                                                                           |

#### Client Document Tracker

| **Element**         | **Minimum v1 content**                                                                                                             |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------|
| Client (Party)      | Required: name, email. Optional: company.                                                                                          |
| Checklist template  | A reusable list of requested items. Each item has a name, required or optional, a due date, and an optional expiry period.         |
| Documents           | Upload by the client or the owner. Status: pending, received, approved, or rejected (with a reason). Expiry date entered manually. |
| Reminders to client | Only while an item is missing: 7 days before due, 3 days before, on the due date, and 3 days after.                                |
| Expiry reminders    | 30 days and 7 days before a document expires.                                                                                      |
| Reminders to owner  | Alert when an item is 7 days overdue.                                                                                              |
| Dashboard           | Clients with missing items, overdue items, items expiring within 30 days, completion percentage per client.                        |
| Not in v1           | AI extraction of expiry dates, e-signature, third-party integrations.                                                              |

## 8. Technical architecture

### 8.1 Overview

The system has three layers: a web frontend, a standalone REST backend, and managed services (database, file storage, email, AI provider). The frontend never talks to the database directly.

### 8.2 Chosen stack

| **Layer**          | **Choice**                                                                                                              | **Notes**                                                                                                                                                                                                                                                                 |
|--------------------|-------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Backend            | Java 21 with Spring Boot 4.1                                                                                              | Founder's preference. Spring Security for authentication, Spring Data JPA, Bean Validation. Separate service so it can serve web and mobile.                                                                                                                              |
| Hosting (backend)  | Render free web service                                                                                                 | Free plan with 750 instance hours per month. Sleeps after 15 minutes without traffic, so a keep-alive ping is required (Section 8.7). Moves to a paid always-on instance when the first customer pays.                                                                    |
| Hosting (frontend) | Vercel (Hobby plan while free)                                                                                          | Hobby is for personal, non-commercial use only. Move to the Pro plan before charging anyone (Section 8.8). The browser talks to Vercel only; Next.js forwards API calls to the backend (Section 8.9).                                                                     |
| API style          | REST with JSON, documented by OpenAPI                                                                                   | Clear contract for the web app, a future mobile app, and AI assistants.                                                                                                                                                                                                   |
| Database           | PostgreSQL on Supabase (free plan), used as plain Postgres only                                                         | JSONB for custom fields, strong transactions for the ledger, tenant isolation, Flyway migrations. Only the Spring Boot service talks to it; Supabase auth and auto-generated APIs are not used.                                                                           |
| Frontend           | Next.js (React, TypeScript)                                                                                             | Responsive web app. Chosen for template landing pages (SEO) plus the app itself.                                                                                                                                                                                          |
| File storage       | Supabase Storage (free plan)                                                                                            | Private bucket and signed URLs. Free plan is 1 GB total with a 50 MB per-file limit. Behind a storage interface; Cloudflare R2 is the planned replacement when space runs low.                                                                                            |
| Email              | Gmail SMTP, fully configurable                                                                                          | Uses the founder Gmail account at first. Needs 2-step verification and an app password. All settings come from environment variables and sit behind a notification interface, so switching sender or provider is a configuration change. Limits and risks in Section 8.7. |
| Background jobs    | Spring scheduling with a database-backed job table (and a lock so only one instance runs a job)                         | Used for reminders, recurring charge generation, and expiry checks. Quartz is an alternative if needs grow.                                                                                                                                                               |
| AI provider        | Decided in phase 5                                                                                                      | Provider-neutral interface; selected by benchmark (Section 10.5). Used only in the later interview phase.                                                                                                                                                                 |
| Authentication     | Own implementation with Spring Security: hashed passwords (Argon2 or BCrypt), short-lived access tokens, refresh tokens | Includes email verification and password reset. Social login is not in v1. Keeps cost at zero and avoids vendor lock-in.                                                                                                                                                  |

**Important:** free tiers and their limits change frequently. Before committing to specific hosts for the database, backend, storage, email, and AI calls, verify current free-tier terms and limits. Known trade-offs to check: free backend hosts may sleep when idle (slow first request), and free databases may have storage caps or pause inactive projects. The architecture is deliberately provider-neutral so that moving hosts is a configuration change, not a rewrite.

### 8.3 Multi-tenancy and isolation

- Every row that belongs to a customer carries a workspace identifier and an app identifier.

- All queries are scoped by these identifiers in a single shared data-access layer, so that individual features cannot forget to filter.

- PostgreSQL row-level security can be added as a second line of defense.

- Files are stored under workspace-scoped paths and only served through short-lived signed URLs after a permission check.

### 8.4 Core data model (conceptual)

| **Entity**                   | **Description**                                                                                                                       |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| User                         | A login identity (owner, staff, or tracked person). Email, password hash, role.                                                       |
| Workspace                    | The customer account. Owns apps, users, billing plan.                                                                                 |
| App                          | One management app inside a workspace. Points to a current App Definition version.                                                    |
| AppDefinitionVersion         | Immutable JSON configuration with a version number and validation status.                                                             |
| Party                        | A tracked person or organization in an app. Standard columns plus JSONB custom fields. Optionally linked to a User for portal access. |
| Record                       | A configurable item such as a unit or a case. JSONB custom fields.                                                                    |
| ChargeSchedule / Charge      | The recurring rule, and each generated amount owed.                                                                                   |
| LedgerEntry                  | Append-only entries for charges, payments, adjustments. Balances are derived, never edited directly.                                  |
| DocumentRequest / Document   | What must be provided, and the uploaded files with status and expiry date.                                                            |
| ReminderRule / ReminderEvent | The rule, and each scheduled or sent notification with delivery status.                                                               |
| AuditLog                     | Append-only record of significant actions.                                                                                            |
| Plan / Subscription          | Free versus paid limits, ready to connect to billing later.                                                                           |

Money handling rule: the ledger is append-only. A mistake is corrected by a reversing entry, not by editing history. Amounts are stored as integer minor units (cents) with a currency code, never as floating-point numbers. The interface shows USD only in v1, but every amount carries its currency code from day one so that the UK launch needs no data migration.

### 8.5 Reminders and scheduling

- A scheduler scans for upcoming due dates and expiries, creates ReminderEvents, and sends them through the notification interface.

- Reminders are idempotent: a retry never sends the same reminder twice.

- Each reminder stores its outcome (queued, sent, failed) so the owner can see what happened. With Gmail SMTP, sent means the mail server accepted it; bounce and open tracking are not available.

- The scheduler enforces a daily sending cap below the Gmail limit (for example 400 per rolling 24 hours) and queues the overflow for the next window, oldest and most overdue first.

- Time zones are stored per app; due dates are evaluated in the app's time zone.

- Unsubscribe and sending-limit handling are required for email compliance and to protect sender reputation.

### 8.6 Future channels

Notifications use a channel interface (email now; SMS and WhatsApp later). Adding a channel means writing one adapter, not changing reminder logic.

### 8.7 Free-tier infrastructure plan

Limits below were checked on October 22, 2026 against provider documentation and recent reports. They change often, so re-verify before relying on a specific number.

| **Component** | **Choice**                                           | **Free limits**                                                                                                                                            | **Watch-outs and upgrade trigger**                                                                                                                                                                                                                                                        |
|---------------|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Backend host  | Render free web service                              | 750 instance hours per month per workspace. 512 MB RAM, 0.1 CPU. Sleeps after 15 minutes without inbound traffic.                                          | Slow Spring Boot startup. May restart at any time. Local disk is lost on restart. Upgrade to the roughly \$7 per month always-on instance when the first customer pays.                                                                                                                   |
| Database      | Supabase free (Postgres)                             | 500 MB database. Project is paused after 7 days of inactivity.                                                                                             | Scheduler activity keeps it from pausing. Upgrade near 400 MB, or when a paying customer needs a no-pause guarantee (Pro plan is about \$25 per month per organization).                                                                                                                  |
| File storage  | Supabase Storage free                                | 1 GB total, 50 MB per file.                                                                                                                                | Documents fill 1 GB quickly. Migrate to Cloudflare R2 (reported 10 GB free) when about 700 MB is used. Keep behind the storage interface.                                                                                                                                                 |
| Email         | Gmail SMTP, configurable                             | Personal Gmail: 500 recipients per rolling 24 hours (Google Workspace: 2,000). Requires 2-step verification and an app password.                           | Sender is always the Gmail address and replies go to that inbox. No bounce or delivery analytics. If Google restricts the account, all reminders stop. No migration trigger is set for now (founder decision); because settings are environment variables, switching later takes minutes. |
| Frontend host | Vercel Hobby                                         | Roughly 100 GB data transfer, 1 million edge requests, 1 million function invocations, and 4 hours of active CPU per month. Commercial use is not allowed. | Fine for development and a free beta. Upgrade to Pro (\$20 per user per month) before charging anyone.                                                                                                                                                                                    |
| Keep-alive    | GitHub Actions scheduled ping to the health endpoint | Minimum interval 5 minutes. Runs can be delayed by 15 to 30 minutes or more under load. Disabled after 60 days without commits in a public repository.     | Founder decision: one pinger is enough for now. A delay longer than 15 minutes lets Render sleep, so push a commit at least every 60 days to keep the schedule active.                                                                                                                    |

#### Design rules that follow from these limits

- The health endpoint is lightweight and does not require a database call.

- The scheduler touches the database at least once a day so the Supabase project is never idle for 7 days.

- Nothing is stored on the local disk. Render can restart the service at any time, so all state lives in Postgres or Supabase Storage.

- Every background job is idempotent and safe to retry after a restart or a missed run.

- Email settings (SMTP host, port, username, app password, sender name, reply-to address, daily cap) are read only from environment variables, never from code. Switching to another Gmail account, a domain-based sender, or a transactional provider must not require a code change.

- The founder Gmail account is used at first. A dedicated FollowWise account is recommended, because a restriction on a personal account would also affect personal mail. Swapping is only a change of the variables.

### 8.8 What the first revenue pays for

Everything runs free until someone pays. These are the upgrades, in the order they become necessary, with approximate prices at the time of checking:

| **Upgrade**                         | **Approx. cost**                      | **When it becomes necessary**                                                               |
|-------------------------------------|---------------------------------------|---------------------------------------------------------------------------------------------|
| Vercel Pro (frontend hosting)       | \$20 per user per month               | Before charging anyone. The Hobby plan is restricted to personal, non-commercial use.       |
| Render always-on instance (backend) | From about \$7 per month              | When the first customer pays, so the service stops sleeping and reminders send reliably.    |
| Supabase Pro (database and storage) | About \$25 per month per organization | When the database nears 400 MB, storage nears the limit, or a no-pause guarantee is needed. |
| Domain name                         | Roughly \$10 to \$15 per year         | When ready for landing pages and a branded sender address.                                  |

Planning note: the first two upgrades total about \$27 per month, and about \$52 with Supabase Pro. Pricing should be set so that the first few paying customers cover this. The exact prices of FollowWise plans remain open (Section 13.3).

### 8.9 Frontend to backend connection

The web app (Vercel) and the API (Render) live on different domains. Calling the API directly from the browser would require cross-origin settings and would run into browser restrictions on cross-site cookies. The planned design avoids both:

- The browser only ever talks to the Next.js site. Next.js forwards requests under /api to the Spring Boot backend, so the browser sees a single origin.

- The login uses a short-lived access token plus a refresh token stored in an HttpOnly cookie issued through the same origin.

- Because the free backend can be asleep, the frontend shows a friendly loading state and retries on the first request.

- This design is to be validated in the walking skeleton at the start of the build (Section 16, step 5). If it fails, the fallback is direct API calls with explicit cross-origin rules.

## 9. Identity and the tracked person's access

**Decision:** tracked people (tenants, clients) get accounts by default in v1. The design must not break anything that is being built, and should leave room for lighter-weight access later.

To keep that flexibility, identity and access are separated:

- A **Party** (the tenant or client as the owner sees them) exists independently of a **User** (a login). An owner can add a Party and track them before they ever sign in.

- When the owner invites a Party, they receive an email invitation to create an account, which links their User to that Party.

- All access checks go through one permission layer that answers "which parties and data may this identity see." Portal screens depend only on that layer, never on how the person logged in.

- Because of this, a passwordless magic-link option can be added later as another way to authenticate into the same Party, with no changes to data or portal screens.

- Reminder emails link to the portal. If the person is not logged in, they sign in first and then land on the right item.

Roles: **Owner** (full control), **Staff** (a team member of the owner with limited permissions, for example recording payments but not deleting the app or seeing billing), and **Portal user** (sees only their own items). Decision: the role field and a single central permission check exist from day one, but the screens to invite staff and manage seats are deferred until the first team-based customer (for example a bookkeeping firm) asks. Staff seats are also a natural paid-plan limit later.

## 10. The AI layer (phase 5)

### 10.1 Role of AI

AI is the engine's interface, not its core. It has three jobs, all of which produce or modify configuration:

1.  **Interview:** ask a short, adaptive set of questions (about 5 to 8) about the business and what needs to be tracked.

2.  **Generate:** produce an App Definition from the answers.

3.  **Refine:** convert chat requests such as "also track security deposits" into a proposed change to the App Definition.

Later, AI can also extract expiry dates and document types from uploads, and draft reminder wording in a suitable tone. These are optional enhancements.

### 10.2 Safety rules

- The model must return JSON that conforms to the App Definition schema. Output that fails validation is rejected and retried or falls back to the closest template.

- The model can only reference block types that exist. It cannot invent new block types or write code.

- Every AI-proposed change is shown to the owner as a preview and applied only after confirmation.

- Customer data is not used to train models. The chosen provider's data-handling terms must be reviewed before launch.

- AI usage is rate-limited per account to control cost.

### 10.3 If the AI is unavailable

Templates continue to work with no AI at all. The AI interview can fail without affecting existing apps. This is another benefit of keeping AI out of the runtime path.

### 10.4 Competition note

Many general AI app builders (Lovable, Base44 and others) and no-code tools (Airtable, Glide, Softr, Zoho Creator) overlap with this idea. "AI builds your app" alone is not a defensible message. The advantage must come from reliability, domain-specific interviews per business type, built-in reminders and portals, and a much simpler experience for one kind of user.

### 10.5 Choosing the AI provider (decided in phase 5)

No provider is chosen yet. Candidates are the free tiers of commercial models (for example Google Gemini Flash models) and open-weight models (for example Gemma) used through a hosted provider or a separate host. The free backend (512 MB RAM) cannot run a model itself. Free commercial tiers may allow the provider to use inputs to improve its models, so only business descriptions from the interview may be sent there, never customer data.

An open-weight model is not assumed to be better or worse than a commercial one. The choice will be made by a benchmark:

- A fixed set of 20 to 30 sample business interviews for the launch templates.

- Measure the share of outputs that pass App Definition schema validation on the first try, the quality of the follow-up questions, speed, cost, and the provider data-handling terms.

- Because the AI sits behind a provider-neutral interface, switching later is a configuration change.

## 11. Security, privacy, and compliance

- Encryption in transit (HTTPS everywhere) and at rest (database and file storage).

- Passwords hashed with a modern algorithm; rate limiting and lockout on login; secure session handling.

- Strict permission checks on every endpoint, with tests that try to read another workspace's data.

- Private file storage; documents served only by short-lived signed links; file type and size validation; malware scanning when budget allows.

- Audit log for sensitive actions.

- Automated daily database backups and a tested restore procedure.

- Privacy policy, terms of service, and data export and deletion on request. A data-retention policy for deleted accounts.

- Do not collect or request highly sensitive identifiers in v1 (full SSNs, bank logins, health data).

- US landlord and tenant rules vary by state (late fees, notices, deposits). The product tracks and reminds but must not present itself as legal advice. A visible disclaimer and conservative defaults are required, and legal review before charging is recommended.

- UK GDPR requirements apply when the UK is added in v2. Designing for data export, deletion, and consent now makes that easier.

This section is engineering guidance, not legal advice. Get qualified review of the terms and privacy policy before accepting paying customers.

## 12. Business model

### 12.1 Pricing structure

- **Free:** one small app with a limit on the number of tracked people. Email reminders included.

- **Paid:** more apps, higher or unlimited tracked people, staff logins, exports, and priority features.

- **Later add-ons:** SMS and WhatsApp reminders, online payment collection (with a possible small fee), e-signature, integrations.

Exact limits and prices are not decided. Treat any numbers as hypotheses to test with real users. A reasonable starting hypothesis is a low monthly price per app for small owners, and a higher price for professionals such as bookkeepers, who manage more clients and lose more money from missed deadlines.

### 12.2 Why people pay

The product recovers late money, prevents lapsed documents and licences, and saves recurring hours of manual follow-up. Multi-app accounts increase switching costs and lifetime value.

### 12.3 Getting the first customers without a network

- One search-friendly landing page per template (for example "rent collection tracker for small landlords").

- Join and observe online communities where the target users complain (landlord, bookkeeping, and freelancing communities), then invite a handful of people for short interviews and free early access. Follow each community's rules on self-promotion.

- Offer the first users free access in exchange for feedback and a testimonial.

- Use their requests to decide the next template.

## 13. Roadmap, risks, and open items

### 13.1 Phases (no dates, by order only)

| **Phase**                    | **Outcome**                                                                                                                                   |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 0\. Foundations              | Repository structure, Spring Boot service, Postgres with migrations, Next.js shell, authentication, workspaces, CI, deployment on free tiers. |
| 1\. Engine core              | App Definition schema and validator; Party, Record, Ledger, Document, Reminder blocks; audit log; dashboard generator.                        |
| 2\. Templates                | Rental Property Manager and Client Document Tracker built as App Definitions; portal; email reminders. Internal testing.                      |
| 3\. Private beta             | First few real users, free. Fix issues, tune reminders, collect requests. Privacy policy and terms in place.                                  |
| 4\. Third template + billing | Tutor/Class Manager to prove modularity. Plans and billing turned on.                                                                         |
| 5\. AI interview             | Interview, generation, and chat-based refinement producing App Definitions with validation and preview.                                       |
| 6\. Growth                   | UK launch, SMS and WhatsApp channels, payment collection, mobile app, more templates.                                                         |

### 13.2 Key risks and mitigations

| **Risk**                                       | **Mitigation**                                                                                                                                                                                                                                                                                                 |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Heavy competition in every vertical            | Narrow focus per template, very low entry price, simple setup, strong reminders and portal.                                                                                                                                                                                                                    |
| Users set up the app and then abandon it       | Prioritize reminders, dashboards, and onboarding that reaches first value fast. Track weekly active use.                                                                                                                                                                                                       |
| Unreliable AI output                           | Config-only output, strict schema validation, preview and confirm, template fallback.                                                                                                                                                                                                                          |
| Schema changes breaking data                   | Versioned definitions, JSONB custom fields, no destructive changes by default.                                                                                                                                                                                                                                 |
| Handling money records and sensitive documents | Append-only ledger, integer amounts, encryption, permissions tests, backups, audit log.                                                                                                                                                                                                                        |
| Free-tier limits and instability               | Provider-neutral interfaces, monitor usage, upgrade when revenue starts.                                                                                                                                                                                                                                       |
| Solo founder bandwidth                         | Small v1 scope, strong reuse via the engine, AI-assisted development, avoid building features nobody asked for.                                                                                                                                                                                                |
| Email via a Gmail account                      | App password, daily cap enforced in code, queueing of overflow, a clear sender name, and fully configurable settings so the sender can be switched quickly if the account is restricted or volume grows. A dedicated account is recommended over a personal one. Bounce tracking stays limited while on Gmail. |
| Service sleeps or restarts on the free host    | Keep-alive pings, idempotent jobs, no local state, and the upgrade to a paid always-on instance with the first paying customer.                                                                                                                                                                                |
| Free storage fills up                          | Storage interface from the start; migrate to Cloudflare R2 at about 700 MB used.                                                                                                                                                                                                                               |

### 13.3 Status of open items

**Resolved:** product name, backend hosting, database, file storage, email setup, frontend hosting, staff roles, currency, minimum template specifications, and the v1 template list (exactly two).

**Intentionally left open (founder decision):**

1.  Exact free-tier user limits and paid prices. To be decided after talking to real users. Section 8.8 gives the cost floor to price against.

2.  AI provider. To be decided in phase 5 by the benchmark in Section 10.5.

**Deferred, with no blocker for the build:**

- Domain purchase, trademark check, and social handles for FollowWise.

- Trigger for moving email off Gmail (none set).

- A second keep-alive pinger (decided against for now).

## 14. Context for AI coding assistants

This section is written as standing instructions for any AI tool used to build the project. Read it before making design or code decisions.

### 14.1 Decision log

| **ID** | **Decision**                                                                                                                          | **Rationale / consequence**                                                                                                                                                 |
|--------|---------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| D-01   | AI generates configuration, not code.                                                                                                 | Reliability and testability. All apps are built from fixed blocks.                                                                                                          |
| D-02   | Templates ship first; AI interview comes later.                                                                                       | Prove the engine with fewer moving parts.                                                                                                                                   |
| D-03   | Launch templates: Rental Property Manager and Client Document Tracker; Tutor/Class Manager third.                                     | Covers both halves of the engine for the US market.                                                                                                                         |
| D-04   | Market: US first, UK as v2.                                                                                                           | Dollar-paying, SaaS-friendly customers reachable online.                                                                                                                    |
| D-05   | Web only in v1; backend separate and API-first.                                                                                       | Same APIs reused by a mobile app later.                                                                                                                                     |
| D-06   | Backend: Java with Spring Boot. Database: PostgreSQL. Frontend: Next.js with TypeScript.                                              | Founder preference and comfort; strong fit for money data and JSONB custom fields.                                                                                          |
| D-07   | Payments are tracked, not collected, in v1.                                                                                           | Avoids fees, compliance, and risk early.                                                                                                                                    |
| D-08   | Email is the only reminder channel in v1.                                                                                             | Free. SMS and WhatsApp become paid add-ons later.                                                                                                                           |
| D-09   | Tracked people get accounts by default; identity is separated from Party so magic links can be added later without breaking anything. | Founder preference; keeps flexibility.                                                                                                                                      |
| D-10   | Sensitive-data protections are required from day one.                                                                                 | Trust is essential for documents and money records.                                                                                                                         |
| D-11   | Free tier of one small app; paid plans beyond that.                                                                                   | Growth through free usage, revenue through multiple apps and larger usage.                                                                                                  |
| D-12   | Zero infrastructure spend initially.                                                                                                  | All services must have usable free tiers and be replaceable.                                                                                                                |
| D-13   | India-focused ideas and the Splitwise-style chat bot are parked.                                                                      | Revisit after the product is proven in the US.                                                                                                                              |
| D-14   | Sell verticals, not the generic engine.                                                                                               | Each template has its own name, page, and wording.                                                                                                                          |
| D-15   | Product name is FollowWise. The engine is called CEM internally.                                                                      | Confirmed by the founder. Domain and trademark still to be checked.                                                                                                         |
| D-16   | Backend hosting: Render free web service with a keep-alive ping.                                                                      | Confirmed by the founder. Upgrade to a paid always-on instance when the first customer pays.                                                                                |
| D-17   | Database: Supabase free, used as plain Postgres.                                                                                      | Spring Boot stays the only backend. No Supabase auth or generated APIs.                                                                                                     |
| D-18   | File storage: Supabase Storage for now, behind an interface.                                                                          | Migrate to Cloudflare R2 when space runs low.                                                                                                                               |
| D-19   | Email: Gmail SMTP using the founder Gmail account at first, behind a notification interface.                                          | Founder choice for higher free volume. Daily cap enforced in code. Settings are environment variables so the sender can be switched later. No migration trigger set (D-28). |
| D-20   | AI provider is decided in phase 5 by benchmark.                                                                                       | Open-weight and commercial models both considered. Provider-neutral interface.                                                                                              |
| D-21   | Permission system and roles are built now; staff invitation screens are deferred.                                                     | Cheap to add later, expensive to retrofit permissions.                                                                                                                      |
| D-22   | Currency code is stored on every amount; the UI is USD-only in v1.                                                                    | No data migration needed for the UK launch.                                                                                                                                 |
| D-23   | Launch templates ship with the minimum specification in Section 7.3.                                                                  | Refine after first user conversations.                                                                                                                                      |
| D-24   | Version 1 contains exactly two templates: Rental Property Manager and Client Document Tracker.                                        | Confirmed by the founder. Tutor / Class Manager is the first post-launch addition.                                                                                          |
| D-25   | Frontend: Next.js on Vercel (Hobby plan while free, Pro before charging).                                                             | Hobby is non-commercial only. Upgrade is the first required spend.                                                                                                          |
| D-26   | All email settings are environment variables; the sender is switchable without code changes.                                          | Founder can move to a domain or provider whenever a domain is bought.                                                                                                       |
| D-27   | One keep-alive pinger (GitHub Actions) is enough for now.                                                                             | Founder decision. No second pinger.                                                                                                                                         |
| D-28   | No trigger is defined for moving email off Gmail.                                                                                     | Founder decision. Risks are recorded in Section 8.7.                                                                                                                        |
| D-29   | Paid plan prices and free-tier user limits stay open. AI provider stays open until phase 5.                                           | Intentionally left open; to be decided with real user feedback and the Section 10.5 benchmark.                                                                              |
| D-30   | The browser talks to Next.js only; Next.js forwards /api to the backend.                                                              | Avoids cross-origin and cross-site cookie problems. Validate in the walking skeleton.                                                                                       |
| D-31 | The keep-alive workflow lives in a separate small public repository (followwise-keepalive). The main repository is private. | Free GitHub Actions minutes for private repos are limited (2,000 per month). A ping every 14 minutes is roughly 3,000 runs a month by estimate, which would exceed that. Public repos get free minutes on standard runners. |
| D-32 | The docs are the project memory: README.md (this file, living source of truth), docs/STATUS.md (single status log), docs/DESIGN.md (UI/UX), docs/plans/ (implementation plans). The Word brief v1.2 is a frozen snapshot and is not regenerated. | One place per kind of information; no drift. |
| D-33 | Model roles: this chat is the orchestrator. Claude models in Antigravity do analysis and implementation plans only and never change code or execute plans. Non-Claude (Gemini) models execute approved plans. Rules are in AGENTS.md, including token-saving rules. | Founder decision; keeps Claude usage cheap and separates planning from execution. |
| D-34 | Version scheme: 0.x while building (0.1.0 after Phase 0, 0.2.0 after Phase 1, 0.3.0 after Phase 2); 1.0.0 when the definition of done (section 16.3) is met. | Makes the current version unambiguous in STATUS.md. |
| D-35 | DESIGN.md is the UI/UX design system. Tailwind plus shadcn/ui and the color and type defaults are proposed, to be confirmed in Phase 0, step 4. | Avoids inventing UI choices ad hoc. |
| D-36 | The backend is deployed on Render as a Docker service (multi-stage Dockerfile in backend/). | Render has no native Java runtime, so Docker is the only option for Spring Boot. |
| D-37 | Build tool: Maven. Local development database: Postgres in Docker Compose. | Simplest for a solo developer; Docker is needed anyway for the Render image. Revisit Testcontainers in PLAN-0002. |
| D-38 | Backend framework is Spring Boot 4.1.x (latest 4.1 patch), not 3.x. | Every Spring Boot 3.x line reached end of open-source support in June 2026 (3.5.16 was the last free release), so it no longer gets security fixes. Boot 4.1 is supported to mid-2027. The skeleton was first built on 3.3.5 and is upgraded in PLAN-0002. Orchestrator error: the PLAN-0001 amendment said "latest 3.x" without checking support status. |
| D-39 | Time handling: the JVM default time zone is UTC and all timestamps are stored in UTC. The per-app time zone applies only to display and reminder scheduling. | Avoids time-zone bugs (for example legacy zone names like Asia/Calcutta failing against Postgres). Introduced in PLAN-0001. |
| D-40 | Local Postgres host port is configurable (POSTGRES_HOST_PORT, default 5432). | The founder machine already uses 5432, so a local .env uses 5433. Other machines should not need to change code. |
| D-41 | Identity model: tables users, workspaces, memberships (user, workspace, role OWNER|STAFF|PORTAL_USER). A user can belong to many workspaces. Registration creates an Owner user plus a first workspace. Portal users are invited later, never self-registered. UUID primary keys, timestamptz, emails normalized to lowercase with a unique index (no citext). | Phase 0, step 3a identity architecture. |
| D-42 | Workspace context is explicit in the URL (/api/workspaces/{workspaceId}/...) and checked by one central access service. Non-members get 404 (no existence leak). Members lacking a permission get 403. The role-to-permission map lives in code. | Tenant isolation and zero information leakage. |
| D-43 | Tokens: access JWT (HS256, JWT_SECRET of at least 32 bytes, 15 min, claims sub/iat/exp/iss only, no workspace claim) validated by Spring Security resource server; authorization is always read from memberships. Refresh token is a random opaque value stored only as a SHA-256 hash, rotated atomically on every use; reuse revokes its whole family (rows kept with revoked_at). It travels in an HttpOnly, SameSite=Strict cookie with Path=/api/auth, Secure from AUTH_COOKIE_SECURE. | Stateless API with secure refresh rotation and reuse detection. |
| D-44 | Passwords: DelegatingPasswordEncoder (bcrypt default), length 10 to 72 UTF-8 bytes, no composition rules. Every login failure, including a locked account, returns the same 401; unknown emails still run a dummy hash. Lockout after 5 failed attempts for 15 minutes using atomic database counters. | Timing-safe authentication and brute-force protection. |
| D-45 | Tests use Testcontainers Postgres 16 so mvn test needs only Docker running. Errors use RFC 9457 problem details (ProblemDetail). | Isolated, reproducible integration testing without local DB dependency. |
| D-46 | CSRF: Spring CSRF is disabled (stateless bearer API). Refresh and logout require the X-FollowWise-Client header and a matching Origin when present; no CORS is enabled because the browser only talks to the Next.js origin. | Protection against cross-site request forgery on cookie-based refresh/logout endpoints. |
| D-47 | Email is sent only through an outbox table (email_outbox: id, kind, to_address, subject, body_text, status QUEUED|SENT|FAILED, attempts, last_error, send_after, sent_at, created_at). Request handlers only enqueue. A scheduled worker (runs only when SCHEDULER_ENABLED=true) claims rows with FOR UPDATE SKIP LOCKED, enforces MAIL_DAILY_CAP over a rolling 24 hours, sends, and retries with backoff up to 5 attempts. Account emails go before bulk reminders. After SENT or FAILED, body_text is overwritten with '[redacted]' so raw token links are not kept. | Guaranteed delivery, rate-cap enforcement, and token redaction. |
| D-48 | EmailSender interface with a Gmail SMTP implementation (Spring Mail) configured only from MAIL_* variables. MAIL_TRANSPORT=smtp|log. The log transport prints the email including links and is allowed only in the local or test profile; startup fails for log in any other profile, for smtp with missing host, username, or password, and for any other MAIL_TRANSPORT value. Plain-text emails only in v1. | Swappable email provider with fail-fast configuration validation. |
| D-49 | Table auth_tokens (id, user_id, type VERIFY_EMAIL|RESET_PASSWORD, token_hash char(64) unique, expires_at, used_at, created_at). Token is 32 random bytes, base64url, stored only as a SHA-256 hash, single use. Verification 24 hours, reset 1 hour. A new token of a type invalidates older unused ones. Links are APP_BASE_URL/verify-email?token=... and APP_BASE_URL/reset-password?token=... | Secure one-time account recovery and verification tokens. |
| D-50 | users.email_verified_at (nullable). Registration logs the user in and enqueues a verification email. Unverified users may use the app (the frontend shows a banner from /api/me emailVerified); limits on emailing third parties are enforced in later plans. POST /api/auth/verify-email; POST /api/auth/resend-verification (authenticated, 1 per 60 seconds, 5 per day). | Email verification flow and rate limiting. |
| D-51 | POST /api/auth/forgot-password always returns 204; it enqueues an email only for existing accounts, at most 3 per hour per email. POST /api/auth/reset-password applies the 10 to 72 byte rule, sets the password, clears lockout, revokes all refresh families, marks the token used, and enqueues a PASSWORD_CHANGED notice. Bad, expired, or used tokens return one generic 400. | Timing-safe password recovery and session revocation. |
| D-52 | Duplicate registration keeps returning 409 (accepted enumeration trade-off; revisit with IP rate limiting). | Known trade-off accepted for v1. |
| D-53 | Email tests use the log transport and mocked senders; no real SMTP in tests or CI. The scheduler is disabled in tests except where the worker itself is under test. | Deterministic and isolated test execution. |
| D-54 | Frontend stack: latest stable Next.js 16 (App Router), TypeScript strict, Tailwind CSS, shadcn/ui, lucide-react, npm, Node >=22. Tokens and components follow docs/DESIGN.md. | Phase 0, step 4 frontend architecture. |
| D-55 | The browser talks only to the Next.js origin. next.config rewrites /api/* to BACKEND_API_URL/api/* (server-side env). The client adds X-FollowWise-Client: web on refresh and logout. No CORS. APP_BASE_URL on the backend must equal the frontend origin. | Origin isolation and CORS elimination. |
| D-56 | The access token lives in memory only. The session is restored by a single-flight POST /api/auth/refresh when /app loads; /app is guarded on the client (no middleware or proxy file, no marker cookie). On a 401 the client refreshes once and retries once, then signs out. Only GETs and refresh auto-retry on 502/503/504; POSTs never auto-retry. | In-memory token security and resilient session recovery. |
| D-57 | Headers via next.config: Referrer-Policy no-referrer on /verify-email and /reset-password only (strict-origin-when-cross-origin elsewhere), nosniff, X-Frame-Options DENY, and a CSP with frame-ancestors 'none'. Token query strings are removed with history.replaceState before any request. | Security headers and token leak prevention. |
| D-58 | Frontend tests: Vitest and React Testing Library for the API client and forms. lint, typecheck, test, and build must pass. No end-to-end tests yet. | Fast unit and component test suite. |
| D-59 | Deployment: backend on Render free web service (Docker, root `backend/`), database on Supabase (Session Pooler only — direct connection is IPv6 and fails on Render free). Frontend on Vercel Hobby (root `frontend/`, Node 22). Keep-alive pings `/health` every 10 min from a public repo `followwise-keepalive` using a GitHub Actions cron and `HEALTH_URL` repository variable. Version 0.1.0 tagged on `main` after founder acceptance. | Phase 0, step 5 end-to-end deployment architecture. |
   | D-60 | Email goes through the Brevo SMTP relay (smtp-relay.brevo.com, port 2525, SMTP key) because Render's free tier blocks outbound ports 25, 465 and 587. Daily cap 250 (Brevo free allows 300). The Gmail SMTP part of D-19 and D-48 is superseded; the EmailSender interface is unchanged. A Gmail address as the From usually lands in spam, so verify a domain in Brevo before public launch (settings only, no code change). | Confirmed by the outbox timeout on smtp.gmail.com:587 and a successful real send through Brevo. |
| D-61 | The canonical schema and templates live in backend/src/main/resources/appdefinition/ because Render builds only from backend/. The root schemas/ folder keeps only a README that points there. | Canonical location for App Definition assets. |
| D-62 | App Definition v1 is JSON validated by a JSON Schema (2020-12) with additionalProperties false everywhere and schemaVersion 1. Identifiers are lowercase slugs (^[a-z][a-z0-9_]{1,40}$). Block kinds, field types, reminder triggers, message keys, widget types, and channels are fixed enumerations; the AI can never invent new ones. Labels are plain text (no control characters, no angle brackets). Money is stored as integer cents with the app currency (USD only in v1). The app has an IANA timezone. There are no expressions or formulas in v1: an amount, day, or date comes from a named field or a fixed value. The definition JSON is limited to 64 KB, with bounded counts (for example party types, fields per type, reminder rules, widgets) that the plan proposes. | Structural schema contract and bounded payload size. |
| D-63 | Validation has two layers that both run on every write: structural (the JSON Schema) and semantic (Java: identifiers unique, every reference resolves to an existing field of the right type, charge amount fields are money, reminder triggers match the blocks they refer to, widgets only use data that exists). Errors are returned as a list of at most 20 items, each with a JSON Pointer path and a message, never a stack trace. | Two-layer validation and sanitised error reporting. |
| D-64 | Definition changes between versions are checked by a transition validator. A new version must not remove or rename any party type, record type, field, charge schedule, document item, select option, reminder rule, or widget that existed; they are marked archived or hidden instead. A field type cannot change. Adding items, relabeling, and hiding are allowed. | Backward compatibility and non-destructive evolvability. |
| D-65 | Templates are hand-written definition files at backend/src/main/resources/appdefinition/templates/{key}.json (keys: rental-property-manager, client-document-tracker), built to README 7.3. A registry loads and validates all of them at startup, and the app refuses to start if any is invalid. Raw custom definitions come later with the AI interview. | Pre-validated launch templates loaded via registry. |
| D-66 | Definition shapes and binding rules (v1): chargeSchedule kind is recurring or late_fee (late fees are off by default and need graceDays); reminder rules name their party type, recipient (party or owner), message key, and, for lease.end, a date field; message keys are checked against trigger, recipient, and offset by a fixed table; documents have items with optional due offsets and expiry days; every identity item can be archived; widget types are split per data source. Transitions also lock kinds, triggers, owning party types, reference targets, and currency. | Phase 1 step 1a definitions and shape binding rules. |
| D-67 | Persistence (new Flyway migration after V4): apps(id uuid PK, workspace_id FK, name, template_key null, current_version int, created_at, updated_at, archived_at null) and app_definition_versions(id uuid PK, app_id FK, version int, definition jsonb, note text null, created_by FK users, created_at, unique(app_id, version)). Version rows are immutable and never updated or deleted in v1. Apps are archived, never hard-deleted. Every query scopes by workspace_id and app_id together: an app id that belongs to another workspace is a 404. | Immutable version history and strict tenant isolation. |
| D-68 | Endpoints: GET /api/templates and GET /api/templates/{key} (authenticated); POST /api/workspaces/{workspaceId}/apps {templateKey, name, timezone optional}; GET .../apps; GET .../apps/{appId} (app plus current definition); GET .../apps/{appId}/versions and .../versions/{n}; PUT .../apps/{appId}/definition {expectedVersion, definition, note optional}; POST .../apps/{appId}/archive. New permissions VIEW_APP (OWNER, STAFF) and MANAGE_APPS (OWNER); a member without the permission gets 403 and a non-member 404 (D-42). Creating from a template copies it, applies the requested name and timezone, validates, and stores version 1 in one transaction. | Minimal surface area for Phase 1 step 1b. |
| D-69 | Saving a definition: expectedVersion check, identical check, structural and semantic validation, transition check, version limit, then one conditional UPDATE on the app row (rows = 0 means 409 stale_version) and the version insert in the same transaction. Validation failure is 422 with at most 20 errors and nothing saved. An identical definition returns 200 and creates nothing. Archived apps cannot be edited (409). | Optimistic locking without a separate locking table. |
| D-70 | Limits by env var: MAX_APPS_PER_WORKSPACE (default 5, active apps; total including archived is capped at 5 times that) and MAX_DEFINITION_VERSIONS_PER_APP (default 200) return 409. Definition request bodies are limited to 80 KB by a filter that checks Content-Length and also counts the bytes actually read, returning 413 problem+json. | Bounded resource usage on the free tier. |
| D-71 | apps.name is 1 to 100 characters with the label rules and always equals the name inside the current definition. The API has no separate rename; renaming is a definition change. | Avoids name drift between the app row and its definition. |
| D-72 | App tables are accessed with JdbcClient and explicit SQL (no JPA entities). jsonb is written with CAST and read as text; the stored definition is compared and returned as a JSON tree. | Clean, explicit SQL persistence for JSON documents. |
| D-73 | Endpoints that accept a definition read the raw body with the strict parser (duplicate keys, unknown keys, depth, and size limits) and never rely on Spring's default JSON binding for the definition. | Prevents silent data corruption and enforces strict payload limits. |
| D-74 | Persistence (Flyway V6, JdbcClient, no JPA): parties(id uuid PK, workspace_id, app_id, party_type text, display_name text not null, data jsonb not null, definition_version int not null, version int not null default 1, created_by, updated_by, created_at, updated_at, archived_at null) and records with the same shape (record_type). Foreign keys use the default RESTRICT, never CASCADE. Every row carries workspace_id and app_id and every query scopes by workspace_id AND app_id (a party or record id from another app or workspace is a 404). Indexes on (app_id, party_type, lower(display_name)) and (app_id, record_type, lower(display_name)). CHECKs: display_name 1 to 200 characters, version >= 1, jsonb is an object, octet_length(data::text) <= 16384. No hard deletes. | Explicit SQL persistence and tenant isolation for parties and records. |
| D-75 | Value rules, enforced in Java on every write against the app's current definition, one error per field with path /data/{fieldId} (at most 20 errors, fixed message text, never echoing the submitted value). Strings are trimmed first; empty after trimming counts as missing. Unknown field ids are rejected. Archived fields are read-only: their stored values are carried over unchanged on update and a different submitted value is an error. Required fields must be present and non-empty on create and cannot be cleared on update. text: 1 to 500 chars, no control characters. email: simple syntax check, at most 254 chars, stored lowercase. phone: digits, spaces, +, -, (, ) only, 5 to 32 chars. date: ISO yyyy-MM-dd and a real calendar date. integer: JSON integer between -1,000,000,000 and 1,000,000,000. money: JSON integer cents between 0 and 100,000,000,000 (never a decimal). boolean: JSON true or false. select: an existing, non-archived option id. party_ref and record_ref: the UUID of an existing party or record of the field's target type in the SAME app and workspace; archived targets are rejected for new or changed references (an unchanged reference to a since-archived target is kept). Serialized data is at most 16 KB. | Comprehensive validation of custom field values per app definition. |
| D-76 | display_name is computed on every write and never accepted from the client: the first 200 characters of the value of the first non-archived text-type field in definition order (tenant: name; unit: address; client: name). If the type has no such field with a value, the write fails with a fixed error. The row also stores definition_version (the version validated against). | Single source of truth for display names derived from custom data. |
| D-77 | Endpoints under /api/workspaces/{wid}/apps/{aid}: POST .../parties {type, data}; GET .../parties?type=&q=&archived=&page=&size= (default size 20, maximum 50, ordered by lower(display_name), id; response {items, page, size, total}); GET, PUT {data, expectedVersion}, POST .../archive and POST .../restore for .../parties/{id}; the same set for .../records. PUT replaces the whole data object (archived-field values are carried over). Permissions VIEW_DATA and EDIT_DATA, both OWNER and STAFF; PORTAL_USER gets 403, non-members 404 (D-42). Writes on an archived app return 409 app_archived; reads still work. A stale expectedVersion returns 409 stale_version with the current version; updates use one conditional UPDATE on version, workspace_id, app_id and archived_at. | Full CRUD and lifecycle API for parties and records. |
| D-78 | Limits (env vars): MAX_PARTIES_PER_APP=500 and MAX_RECORDS_PER_APP=500 (properties app.max-parties-per-app and app.max-records-per-app). A create requires non-archived rows < limit and total rows including archived < 2 x limit, otherwise 409 limit_exceeded. The insert path first locks the app row (SELECT id FROM apps WHERE id=:aid AND workspace_id=:wid AND archived_at IS NULL FOR UPDATE, 409 app_archived if no row is locked) so concurrent creates cannot exceed the limit. q is a case-insensitive prefix match (lower(display_name) LIKE :p ESCAPE '\') with \, % and _ escaped, at most 100 chars; page is zero-based. | Resource bounds and concurrency controls. |
| D-79 | Archived parties and records stay readable and keep existing references, but cannot be referenced by new or changed writes. The raw-body strict parsing, a counting size filter limited to 32 KB for these endpoints, and fixed-text problem+json errors from D-72 and D-73 apply to every new endpoint. | Safe archiving lifecycle and strict payload protections. |

### 14.2 Rules for AI assistants

1.  Never generate or execute code on behalf of end customers. Features that vary per customer must be expressed as configuration.

2.  Validate every App Definition against the schema on every write. Keep the schema versioned and documented.

3.  Add new capabilities as reusable blocks, not as template-specific code. If a template needs something, first ask whether another template could use it.

4.  Keep the backend independent of the frontend. All functionality must be available through documented REST endpoints.

5.  Scope every query by workspace and app through the shared data-access layer. Write tests that try to cross tenant boundaries.

6.  Store money as integer minor units plus a currency code. The ledger is append-only; corrections are reversing entries.

7.  Use database migrations (Flyway) for every schema change. Never change a deployed migration.

8.  Put external services (email, storage, AI, billing) behind interfaces so providers can be swapped.

9.  Keep reminders idempotent and observable. Record delivery status for every notification.

10. Never write to the local disk for anything that must survive. The host can restart at any time.

11. Enforce the daily email sending cap in code and queue the overflow. Never send directly from a request handler.

12. Keep the health endpoint lightweight, and make the scheduler touch the database at least once a day.

13. Build the permission layer first, with Owner, Staff, and Portal user roles, even though staff invitation screens are not in v1.

14. Read every external setting (email, storage, database, AI, API URLs) from environment variables. Never hard-code credentials, sender addresses, or hostnames.

15. Build a thin vertical slice first (one working path from the browser to the database and back, deployed) before building any breadth.

16. Do not add features outside the v1 scope in Section 7 without asking the founder.

17. Prefer simple, boring, well-documented solutions. The team is one person.

18. When a requirement is ambiguous, ask instead of assuming. Record the answer in the decision log.

19. Write automated tests for blocks, permissions, and ledger math first; these carry the most risk.

### 14.3 Suggested repository layout

```
followwise/
├── AGENTS.md           Rules for every AI model (roles, workflow, token rules, engineering rules)
├── README.md           This document: the living project brief and decision log
├── .env.example        Environment variable names (no values)
├── .gitignore
├── docs/
│   ├── STATUS.md       Single status log: version, phase, task log
│   ├── DESIGN.md       UI/UX design system
│   └── plans/          One plan file per task (TEMPLATE.md + PLAN-NNNN-slug.md)
├── backend/            Spring Boot service (Phase 0, step 2)
├── frontend/           Next.js web app (Phase 0, step 4)
├── schemas/            App Definition JSON schema and templates (Phase 1)
└── infra/              Deployment notes and host configuration
```
The keep-alive workflow lives in a separate small public repository, followwise-keepalive.

### 14.4 Glossary

| **Term**       | **Meaning**                                                                                     |
|----------------|-------------------------------------------------------------------------------------------------|
| Workspace      | A customer account that can contain multiple apps.                                              |
| App            | One management app (for example a rental manager) in a workspace.                               |
| App Definition | Versioned JSON configuration describing an app's blocks.                                        |
| Block          | A reusable, tested engine component (party, ledger, reminders, and so on).                      |
| Template       | A hand-written App Definition for a specific business type.                                     |
| Party          | A tracked person or organization (tenant, client, student, vendor).                             |
| Portal         | The simple view the tracked person uses to see and complete their items.                        |
| Owner          | The business user who runs an app.                                                              |
| Engine (CEM)   | The Configurable Engine for Management: the shared core that every FollowWise template runs on. |

## 15. Naming

**The product name is FollowWise (confirmed).** It reflects following up with people, which is the core job of the product. The engine behind it is called the Configurable Engine for Management (CEM) in technical discussion and is never shown to customers.

Each template is sold under a descriptive name, for example "FollowWise for Landlords" and "FollowWise for Bookkeepers" (working names). A tutor and class manager follows as the third template.

Deferred by the founder, with no blocker for the build: check that a suitable domain is available, search US trademarks for conflicts, and check social handles. A domain will be needed for landing pages and, if wanted, a branded sender address. Because email settings are configurable, buying a domain later requires no code change.

## 16. Build plan and next steps

Steps are in order. Each step ends with something that works and can be checked. There are no dates, because the founder works on this as time allows.

### 16.1 Parallel track: talk to users (no code needed)

Building alone for a long time without talking to users is the biggest risk to this project. Start this track now and keep it running during the whole build.

- Find 10 small landlords and 10 bookkeepers or small accounting practices in online communities, following each community's rules on self-promotion.

- Ask each to walk through their last painful week of chasing rent or documents. Do not pitch. Write down exact words and current tools.

- Ask who would try a free private beta. Aim for 3 to 5 people per template before building the dashboards.

- Use what you learn to correct the minimum template specifications in Section 7.3.

### 16.2 Build track

#### Phase 0: foundations (walking skeleton)

1.  Create the accounts and repository: GitHub (one repository with backend, frontend, schemas, docs, and infra folders), Render, Supabase, Vercel, and a Gmail app password. Store all secrets as environment variables.

2.  Backend skeleton: Spring Boot 4.1 with Java 21, Flyway migrations, a lightweight health endpoint, configuration from environment variables, and an OpenAPI description.

3.  Identity: users, workspaces, roles (Owner, Staff, Portal user), sign-up, login, refresh tokens, email verification, password reset, and the central permission check with tests that try to cross workspace boundaries.

4.  Frontend skeleton: Next.js with login and sign-up pages, a simple dashboard shell, and the /api forwarding to the backend.

5.  Deploy the skeleton end to end: backend on Render, database on Supabase, frontend on Vercel, and the keep-alive workflow. Check the full path from signing up in the browser to a row in the database, and confirm the Section 8.9 design works.

#### Phase 1: engine core

1.  App Definition JSON schema, validator, and versioning, with a handful of hand-written sample definitions as test fixtures.

2.  Blocks in this order: parties and records with custom fields, charge schedules and the append-only ledger, document requests and the document store (Supabase Storage behind an interface), then audit log.

3.  Reminder engine: scheduler, idempotent reminder events, the email adapter with the daily cap and queue, and delivery status. Test with a sandbox mailbox before sending to anyone real.

4.  Auto-generated forms and dashboard widgets driven by an App Definition.

#### Phase 2: the two templates

1.  Rental Property Manager as an App Definition built to the Section 7.3 specification, plus its tenant portal.

2.  Client Document Tracker as an App Definition built to the Section 7.3 specification, plus its client portal.

3.  Onboarding that gets a new owner from sign-up to a first tracked person quickly, with sample data they can delete.

4.  Landing page for each template, privacy policy, terms of service, and data export and deletion.

#### Phase 3: private beta

1.  Invite the beta users found in the parallel track, free of charge. Watch weekly active use, not sign-ups.

2.  Fix what breaks, tune reminder wording and timing, and record every request. Decide the first prices from what they say.

3.  Before charging anyone: move the frontend to Vercel Pro, move the backend to a paid always-on instance, and get a legal review of the terms and privacy policy.

### 16.3 Definition of done for v1

- A new owner can sign up, pick one of the two templates, add people, and see a correct dashboard without help.

- Reminders send on time, never twice, and never exceed the daily cap, even after a restart.

- A tracked person can sign in, see only their own items, and upload a document or see what they owe.

- Tests prove that one workspace cannot read another workspace's data, and that ledger balances are always correct.

- Daily backups exist and a restore has been tested at least once.

### 16.4 How to work with the AI coding assistant

- Give it this document at the start of every session and point to Section 14.

- Ask for one step at a time from the build track, and ask it to write tests for permissions and ledger math before the features that depend on them.

- Review every schema change and every permission rule yourself. These are the places where mistakes are expensive.

- Whenever a decision changes, update the decision log first, then the code.