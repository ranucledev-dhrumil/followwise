# PLAN-0007: App Definition schema and validator

Status: DONE
Phase / step: Phase 1, step 1a
Author: Claude (planner)   Executor: Gemini 3.8 Flash
Branch: plan-0007

## Goal
Implement `com.followwise.appdef` — a pure library package (no DB, no HTTP) containing the JSON Schema, structural validator, semantic validator, transition validator, the two launch template definitions, and a startup registry that rejects invalid templates.

## Out of scope
Database tables, HTTP endpoints, frontend, AI, forms and dashboard rendering, reminder sending.

## VERIFY results

**V1 – JSON Schema 2020-12 library for Jackson 3** (github.com/networknt/json-schema-validator):
`com.networknt:json-schema-validator` **3.x** natively uses `tools.jackson.*` (Jackson 3) and supports Draft 2020-12. Add to `pom.xml`:
```xml
<dependency>
  <groupId>com.networknt</groupId>
  <artifactId>json-schema-validator</artifactId>
  <version>3.0.8</version>
</dependency>
```
Trade-off: one new compile-scope dependency (~700 KB jar); alternative is manual checks in tests only, but that loses the authoritative schema contract at runtime.

**V2 – Strict Jackson 3 parsing** (baeldung.com/jackson, github.com):
Use a dedicated `ObjectMapper` bean (not the global web mapper) built with:
- `JsonReadFeature.STRICT_DUPLICATE_DETECTION` — rejects duplicate keys
- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` — rejects unknown fields
- `StreamReadConstraints` — `maxNestingDepth(20)`, `maxStringLength(65536)`
- Check `byte[]` length ≤ 65 536 before parsing

**V3 – Classpath resources in fat jar** (stackoverflow.com):
Use `new ClassPathResource("appdefinition/schema.json").getInputStream()` — never `ResourceUtils.getFile()`. Works inside nested Spring Boot fat jar without extracting to disk. The networknt factory also accepts `InputStream` directly.

## Inputs
`README sections 6, 7.3, 10.2, 14.2; D-01, D-14, D-38, D-39, D-61 to D-65`

## Files to create or change

| Path | Action | Purpose |
|---|---|---|
| `backend/src/main/resources/appdefinition/app-definition-v1.schema.json` | create | JSON Schema 2020-12 |
| `backend/src/main/resources/appdefinition/templates/rental-property-manager.json` | create | RPM template |
| `backend/src/main/resources/appdefinition/templates/client-document-tracker.json` | create | CDT template |
| `backend/src/main/java/com/followwise/appdef/` | create (package) | validator library |
| `backend/pom.xml` | modify | add networknt 3.0.8 |
| `schemas/README.md` | modify | point to backend/src/main/resources/appdefinition/ |
| `README.md` | modify | append D-61–D-65 to section 14.1 |
| `docs/STATUS.md` | modify | Phase 1 steps, tracker, task log |

## Steps

### Slice 1 — Schema and enumerations (commit: `feat(appdef): JSON Schema 2020-12 and enumerations`)
1. Add `com.networknt:json-schema-validator:3.0.8` to `backend/pom.xml`.
2. Create `app-definition-v1.schema.json` (`$schema` 2020-12, `schemaVersion: 1`, `additionalProperties: false` everywhere).

**Fixed enumerations (exhaustive for v1):**
- `fieldType`: `text`, `email`, `phone`, `date`, `integer`, `money`, `boolean`, `select`, `party_ref`, `record_ref`
- `blockKind`: `party`, `record`, `chargeSchedule`, `documentChecklist`, `reminderRule`, `dashboard`
- `reminderTrigger`: `charge.due`, `charge.overdue`, `document.due`, `document.expiry`, `lease.end`
- `messageKey`: `charge_reminder_before`, `charge_reminder_due`, `charge_reminder_late`, `charge_overdue_owner`, `doc_reminder_before`, `doc_reminder_due`, `doc_reminder_late`, `doc_expiry_reminder`, `doc_overdue_owner`, `lease_ending_owner`
- `widgetType`: `count_overdue`, `sum_overdue`, `sum_expected_period`, `sum_collected_period`, `list_overdue_parties`, `list_expiring_leases`, `list_missing_docs`, `list_expiring_docs`, `completion_pct_by_party`
- `channel`: `email`; `frequency`: `monthly`, `once`; `amountSource`: `field`, `fixed`

**Bounded counts:** party types ≤ 5; fields/type ≤ 30; select options ≤ 50; charge schedules ≤ 10; document items ≤ 20; reminder rules ≤ 20; widgets ≤ 10; label ≤ 120 chars; JSON ≤ 65 536 bytes.

Check: `cd backend && mvn test` (compile + 45 existing tests green).

### Slice 2 — Structural and semantic validators (commit: `feat(appdef): structural and semantic validators`)
Create in `com.followwise.appdef`: `ValidationError` record (`path`, `message`); `AppDefinitionValidator` — size check, strict `ObjectMapper` (duplicate keys, unknown props, max nesting depth 20), networknt JSON Schema, then `SemanticValidator`; returns `List<ValidationError>` capped at 20, no stack traces. `SemanticValidator` checks: identifiers unique per scope; all field refs resolve; charge amount refs point to `money` fields; reminder triggers match present blocks; widgets use existing data.
Check: `cd backend && mvn test` — unit tests in `AppDefinitionValidatorTest` covering all required invalid cases.

### Slice 3 — Transition validator (commit: `feat(appdef): transition validator`)
`TransitionValidator` in `com.followwise.appdef`: allowed = add, relabel, set `archived`/`hidden`; rejected = remove/rename party type, record type, field, charge schedule, document item, select option, reminder rule, widget; change `fieldType`.
Check: `cd backend && mvn test` — `TransitionValidatorTest` covering all required cases.

### Slice 4 — Template definitions (commit: `feat(appdef): RPM and CDT template definitions`)
RPM: 1 party type (tenant: name/email/phone/monthlyRent/dueDay/leaseStart/leaseEnd), 1 record type (unit: address/defaultRent/status), 2 charge schedules, 4 tenant reminders (−3/0/+3/+7 `charge.due`), 2 owner reminders (+7 `charge.overdue`, −60 `lease.end`), 5 widgets.
CDT: 1 party type (client: name/email/company), 1 document checklist, 4 client reminders (−7/−3/0/+3 `document.due`), 2 expiry reminders (−30/−7 `document.expiry`), 1 owner reminder, 4 widgets.
Check: Both pass `AppDefinitionValidator` (verified in `TemplateRegistryTest`).

### Slice 5 — Template registry and startup validation (commit: `feat(appdef): template registry with startup validation`)
`TemplateRegistry` — `@Component` with `@PostConstruct`; scans `classpath:/appdefinition/templates/` via `PathMatchingResourcePatternResolver`; throws `IllegalStateException` on any invalid template.
Check: `cd backend && mvn test` — `TemplateRegistryTest`: both templates load; invalid fixture causes `IllegalStateException`.

### Slice 6 — Documentation and decision log (commit: `docs: PLAN-0007 decisions and STATUS update`)
Update `schemas/README.md`; append D-61–D-65 to README 14.1 (verbatim from Decisions block below); update `docs/STATUS.md` per STATUS.md update section.
Check: `Select-String "D-65" README.md` finds the row.

## Checks (full test suite)
Run `cd backend && mvn test` after each slice. All 45 existing tests must still pass. New tests required:

`AppDefinitionValidatorTest`:
- Both templates validate (no errors)
- Unknown property → pointer + message, no stack trace
- Unknown blockKind → rejected
- Bad slug (uppercase, starts with digit) → rejected
- Duplicate field id within party type → rejected
- Dangling field reference in charge schedule → rejected
- Wrong field type for money reference (e.g. `text` field used as amount) → rejected
- Negative fixed amount → rejected; offsetDays out of range (> 365) → rejected
- Label with `<` or `>` → rejected
- Too many fields (> 30) → rejected
- JSON > 65 536 bytes → rejected before parsing
- Duplicate JSON key → rejected
- Nesting depth > 20 → rejected
- Error list capped at 20 items even with 30+ violations
- No stack trace text in any ValidationError message

`TransitionValidatorTest`:
- Adding a field → allowed (empty error list)
- Removing a field → rejected
- Removing a party type → rejected
- Changing a field's fieldType → rejected
- Removing a select option → rejected
- Hiding (archived: true) → allowed
- Relabeling (label changed) → allowed

`TemplateRegistryTest`:
- Registry loads and validates both real templates → no errors
- Registry with one fixture template containing an unknown property → throws `IllegalStateException`

## Acceptance criteria
- [x] `mvn test` green (45 + new tests)
- [x] Both template JSON files validate against `app-definition-v1.schema.json`
- [x] Startup fails with an invalid template fixture
- [x] No ValidationError contains a stack trace or raw input echo
- [x] D-61 to D-65 appended to README 14.1 verbatim
- [x] STATUS.md Phase 1 steps checklist present, 1a ticked

## Risks and assumptions
1. `json-schema-validator` 3.0.8 is the current latest 3.x; executor must confirm version on Maven Central before adding.
2. `additionalProperties: false` in the schema is the primary guard against unknown properties in structural validation; the strict ObjectMapper `FAIL_ON_UNKNOWN_PROPERTIES` applies only to any future Java-model path.
3. `PathMatchingResourcePatternResolver` works in fat jar for `classpath*:` patterns; confirmed via Spring docs.
4. No DB or HTTP code is introduced in this plan.

## Decisions needed
None. All decisions D-61 to D-65 were pre-approved by the orchestrator.

---

## Decisions block (executor appends to README section 14.1)

**D-61** The canonical schema and templates live in backend/src/main/resources/appdefinition/ because Render builds only from backend/. The root schemas/ folder keeps only a README that points there.

**D-62** App Definition v1 is JSON validated by a JSON Schema (2020-12) with additionalProperties false everywhere and schemaVersion 1. Identifiers are lowercase slugs (^[a-z][a-z0-9_]{1,40}$). Block kinds, field types, reminder triggers, message keys, widget types, and channels are fixed enumerations; the AI can never invent new ones. Labels are plain text (no control characters, no angle brackets). Money is stored as integer cents with the app currency (USD only in v1). The app has an IANA timezone. There are no expressions or formulas in v1: an amount, day, or date comes from a named field or a fixed value. The definition JSON is limited to 64 KB, with bounded counts (for example party types, fields per type, reminder rules, widgets) that the plan proposes.

**D-63** Validation has two layers that both run on every write: structural (the JSON Schema) and semantic (Java: identifiers unique, every reference resolves to an existing field of the right type, charge amount fields are money, reminder triggers match the blocks they refer to, widgets only use data that exists). Errors are returned as a list of at most 20 items, each with a JSON Pointer path and a message, never a stack trace.

**D-64** Definition changes between versions are checked by a transition validator. A new version must not remove or rename any party type, record type, field, charge schedule, document item, select option, reminder rule, or widget that existed; they are marked archived or hidden instead. A field type cannot change. Adding items, relabeling, and hiding are allowed.

**D-65** Templates are hand-written definition files at backend/src/main/resources/appdefinition/templates/{key}.json (keys: rental-property-manager, client-document-tracker), built to README 7.3. A registry loads and validates all of them at startup, and the app refuses to start if any is invalid. Raw custom definitions come later with the AI interview.

---

## STATUS.md update (executor does this)
- Add task log line: `| 20YY-MM-DD | PLAN-0007: App Definition schema and validator (Phase 1, step 1a) | <model> | Done |`
- Add Phase 1 steps checklist below Phase 0 steps:
  ```
  ## Phase 1 steps
  - [x] 1a. App Definition schema and validator (PLAN-0007)
  - [ ] 1b. Apps and definition versions API (PLAN-0008)
  - [ ] 1c. Parties and records with custom fields
  - [ ] 1d. Charges and ledger
  - [ ] 1e. Documents and storage
  - [ ] 1f. Reminder engine
  - [ ] 1g. Forms and dashboard generation
  ```
- Set Phase tracker row for Phase 1 to `IN PROGRESS`.
- Set Next task to `PLAN-0008: apps and definition versions API (Phase 1, step 1b)`.

---

## Orchestrator amendments (binding, override anything above that conflicts)
A. Git: ignore every "commit:" line. Run no git command. After each slice print "SLICE n DONE: <suggested commit message>". The Execution report lists files changed per slice.
B. Dependency: use the newest 3.x of com.networknt:json-schema-validator on Maven Central (not necessarily 3.0.8). Record the version. Run `cd backend && mvn dependency:tree` and confirm a single Jackson 3 (tools.jackson) version. For strict duplicate keys use the Jackson 3 StreamReadFeature.STRICT_DUPLICATE_DETECTION (verify the class name; JsonReadFeature is probably wrong). The duplicate-key test must prove it works.
C. Top level of a definition: schemaVersion (const 1), template (key), name (label), currency (const "USD"), timezone (IANA id; semantic check with ZoneId), then the block lists.
D. Shapes (all objects additionalProperties false; every identity item also has optional archived:boolean; select options are {id, label, archived}):
 - partyType {id,label,fields[]}; recordType {id,label,fields[]}. Field {id,label,type,required}; select fields have options[]; party_ref and record_ref fields have target (an existing party or record type id). Tenant in RPM has a "unit" record_ref to the unit record type.
 - chargeSchedule {id,label,kind recurring|late_fee,partyType,amountSource field|fixed, amountField|amountCents, enabled, plus: recurring: dueDaySource field|fixed, dueDayField|dueDay(1-28); late_fee: graceDays 0-60}. Replace frequency/once. RPM has two: monthly rent (recurring, enabled) and late fee (late_fee, fixed amount, enabled=false by default).
 - reminderRule {id,label,partyType,trigger,offsetDays -365..365,recipient party|owner,messageKey,channel email,enabled, dateField (required only for lease.end; must be a date field of partyType)}.
 - documentChecklist {id,label,partyType,items[{id,label,required,dueOffsetDays 0..365 optional,expiryDays 1..3650 optional}]}.
 - widget {id,type,partyType optional, dateField (required for list_expiring_leases; must be a date field)}.
E. Widget enum changes: replace count_overdue and sum_overdue with count_overdue_charges and sum_overdue_charges; add count_overdue_docs. Charge widgets require at least one chargeSchedule; doc widgets require a documentChecklist.
F. messageKey must match trigger and recipient (semantic check, fixed table):
 - charge.due + party: charge_reminder_before (offset<0), charge_reminder_due (=0), charge_reminder_late (>0)
 - charge.overdue + owner: charge_overdue_owner (offset 0..90)
 - document.due + party: doc_reminder_before (<0), doc_reminder_due (=0), doc_reminder_late (>0); document.due + owner: doc_overdue_owner (>0)
 - document.expiry + party: doc_expiry_reminder (<0)
 - lease.end + owner: lease_ending_owner (<0)
 Any other combination is rejected with the pointer of the messageKey.
G. Errors: ValidationError.message comes from a fixed internal table keyed by the validation keyword (for example "unknown property", "value not allowed", "too many items", "too long"), never from library text and never containing instance values. Truncate pointer paths to 200 characters. Add tests: a label of 2000 characters with odd characters and a label with a control character are rejected and their content appears nowhere in the errors.
H. Transition validator: items are matched by id. Also reject changing: field type, party_ref/record_ref target, chargeSchedule kind and partyType, reminderRule trigger and partyType, block kind, currency. Allowed: add, relabel, archive or unarchive, hide, change timezone (valid IANA). Extra tests: removing a charge schedule rejected; changing a reminder trigger rejected; changing a ref target rejected; changing currency rejected; unarchive allowed.
I. Registry: TemplateRegistry takes the classpath location in its constructor (default classpath*:appdefinition/templates/*.json) so tests can point at a fixture folder. The "template" property must equal the file name without .json, or startup fails. Registry returns defensive copies of parsed definitions.
J. Template content fixes: RPM tenant gets the unit link; late fee schedule present (disabled); both owner reminders use the F table; CDT has an "overdue items" widget (count_overdue_docs). Both templates must follow README 7.3 exactly otherwise.
K. Slice 6 check uses `grep -n "D-65" README.md` (Git Bash), not Select-String.
L. Add one more decision to append after D-65:
D-66 Definition shapes and binding rules (v1): chargeSchedule kind is recurring or late_fee (late fees are off by default and need graceDays); reminder rules name their party type, recipient (party or owner), message key, and, for lease.end, a date field; message keys are checked against trigger, recipient, and offset by a fixed table; documents have items with optional due offsets and expiry days; every identity item can be archived; widget types are split per data source. Transitions also lock kinds, triggers, owning party types, reference targets, and currency.

---
## Execution report (executor fills)
Date: 2026-11-04
Executor model: Gemini 3.8 Flash
Deviations from plan: Per Jackson 3 API changes, configured StreamReadFeature.STRICT_DUPLICATE_DETECTION and StreamReadConstraints on JsonFactory.builder(), and instantiated NetworkNT 3.0.8 using SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).
Exact dependency versions used: com.networknt:json-schema-validator:3.0.8, tools.jackson.core:jackson-databind:3.1.5
Files changed by slice:
- Slice 1: backend/pom.xml, backend/src/main/resources/appdefinition/app-definition-v1.schema.json
- Slice 2: backend/src/main/java/com/followwise/appdef/ValidationError.java, backend/src/main/java/com/followwise/appdef/SemanticValidator.java, backend/src/main/java/com/followwise/appdef/AppDefinitionValidator.java, backend/src/test/java/com/followwise/appdef/AppDefinitionValidatorTest.java
- Slice 3: backend/src/main/java/com/followwise/appdef/TransitionValidator.java, backend/src/test/java/com/followwise/appdef/TransitionValidatorTest.java
- Slice 4: backend/src/main/resources/appdefinition/templates/rental-property-manager.json, backend/src/main/resources/appdefinition/templates/client-document-tracker.json, backend/src/test/java/com/followwise/appdef/AppDefinitionValidatorTest.java
- Slice 5: backend/src/main/java/com/followwise/appdef/TemplateRegistry.java, backend/src/test/java/com/followwise/appdef/TemplateRegistryTest.java, backend/src/test/resources/fixtures/invalid-templates/invalid-property.json, backend/src/test/resources/fixtures/mismatched-templates/mismatched.json
- Slice 6: schemas/README.md, README.md, docs/STATUS.md, docs/plans/PLAN-0007-app-definition-schema.md
Slice 1 check result: PASS (mvn test, 45 tests passed)
Slice 2 check result: PASS (mvn test, 59 tests passed)
Slice 3 check result: PASS (mvn test, 73 tests passed)
Slice 4 check result: PASS (mvn test, 74 tests passed)
Slice 5 check result: PASS (mvn test, 78 tests passed)
Slice 6 check result: PASS (grep -n "D-65" README.md passed)
Test counts (existing + new): 45 existing + 33 new = 78 total (15 AppDefinitionValidatorTest + 14 TransitionValidatorTest + 4 TemplateRegistryTest).


## Orchestrator addendum 1 (binding). Scope: tests and small fixes only, no new features.
1. Timezone: require ZoneId.getAvailableZoneIds().contains(tz) in SemanticValidator and TransitionValidator. ZoneId.of also accepts offsets like "+05:00" and "UTC+5", which are not IANA region ids.
2. Parse failures (malformed JSON, duplicate key, nesting too deep, oversized) map to fixed messages. e.getMessage() may only choose the category and must never be copied into a ValidationError.
3. Implement any of these rules that are missing, and test each one:
 a. A top-level unknown key is rejected with its pointer (for example /invoices).
 b. Amendment F table: wrong trigger for a messageKey, wrong recipient, wrong offset sign or range.
 c. Invalid timezone (unknown region, "+05:00", "UTC+5") and non-USD currency.
 d. Charge widgets need a chargeSchedule; document widgets need a documentChecklist; list_expiring_leases needs a dateField that is a date-type field of its partyType; dateField on any other widget is rejected.
 e. A lease.end reminder needs a dateField that is a date-type field of the same partyType; dateField on other triggers is rejected.
 f. chargeSchedule: recurring needs dueDaySource with dueDay 1-28 or an integer dueDayField and forbids graceDays; late_fee needs graceDays 0-60 and forbids dueDay fields; amountField must be money; partyType must exist.
 g. Dangling references are rejected: party_ref and record_ref targets, and the partyType of reminder rules, charge schedules, and checklists.
 h. Count limits (one parameterized test): party types 5, charge schedules 10, reminder rules 20, widgets 10, select options 50, document items 20. The limit passes; limit+1 is rejected.
 i. Malformed JSON that contains the marker text SECRET_MARKER_123: the marker appears nowhere in the errors, and the message is fixed text.
4. Add the tests with clear names. Do not remove or weaken any existing test.
5. Report: new test names, which rules were missing and added, and the final test counts.

## Execution report addendum 1
Date: 2026-11-04
Executor model: Gemini 3.8 Flash
Rules that were missing and added:
- Timezone validation updated in SemanticValidator and TransitionValidator to require `ZoneId.getAvailableZoneIds().contains(tz)`, rejecting non-region offset IDs like `+05:00` and `UTC+5`.
- Top-level unknown property reporting updated in AppDefinitionValidator to append `err.getProperty()` (e.g. `/invoices`), correctly pointing to the offending key.
- dateField forbidden on non-lease.end reminder rules added to SemanticValidator.
- dateField forbidden on non-list_expiring_leases widgets added to SemanticValidator.
- chargeSchedule recurring vs late_fee constraints enforced in SemanticValidator: recurring forbids graceDays and enforces dueDaySource with dueDay 1-28 / dueDayField; late_fee enforces graceDays 0-60 and forbids dueDaySource/dueDay/dueDayField.
New test names:
- AppDefinitionValidatorTest.topLevelUnknownKey_rejectedWithPointer
- AppDefinitionValidatorTest.amendmentFMessageKeyTable_violationsRejected
- AppDefinitionValidatorTest.invalidTimezoneAndCurrency_rejected
- AppDefinitionValidatorTest.widgetDataDependenciesAndDateField_validated
- AppDefinitionValidatorTest.reminderRuleDateField_validated
- AppDefinitionValidatorTest.chargeScheduleRecurringAndLateFeeRules_validated
- AppDefinitionValidatorTest.danglingReferences_rejected
- AppDefinitionValidatorTest.countLimits_limitPassesAndLimitPlusOneRejected (parameterized across 6 categories)
- AppDefinitionValidatorTest.malformedJson_secretMarkerNotLeaked
- TransitionValidatorTest.changingTimezone_offsetsRejected
Files changed:
- backend/src/main/java/com/followwise/appdef/AppDefinitionValidator.java
- backend/src/main/java/com/followwise/appdef/SemanticValidator.java
- backend/src/main/java/com/followwise/appdef/TransitionValidator.java
- backend/src/test/java/com/followwise/appdef/AppDefinitionValidatorTest.java
- backend/src/test/java/com/followwise/appdef/TransitionValidatorTest.java
- docs/plans/PLAN-0007-app-definition-schema.md
Final test counts:
- 93 total tests passing (45 existing pre-plan + 48 appdef tests: 29 AppDefinitionValidatorTest + 15 TransitionValidatorTest + 4 TemplateRegistryTest).