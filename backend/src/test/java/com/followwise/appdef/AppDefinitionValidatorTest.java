package com.followwise.appdef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppDefinitionValidatorTest {

    private AppDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AppDefinitionValidator();
    }

    private String validDefinition() {
        return """
                {
                  "schemaVersion": 1,
                  "template": "rental-property-manager",
                  "name": "Maple Street Rentals",
                  "currency": "USD",
                  "timezone": "America/New_York",
                  "partyTypes": [
                    {
                      "id": "tenant",
                      "label": "Tenant",
                      "fields": [
                        {
                          "id": "name",
                          "label": "Full Name",
                          "type": "text",
                          "required": true
                        },
                        {
                          "id": "rent_amount",
                          "label": "Rent Amount",
                          "type": "money",
                          "required": true
                        }
                      ]
                    }
                  ],
                  "chargeSchedules": [
                    {
                      "id": "monthly_rent",
                      "label": "Monthly Rent",
                      "kind": "recurring",
                      "partyType": "tenant",
                      "amountSource": "field",
                      "amountField": "rent_amount",
                      "dueDaySource": "fixed",
                      "dueDay": 1,
                      "enabled": true
                    }
                  ]
                }
                """;
    }

    @Test
    void validDefinitionPasses() {
        List<ValidationError> errors = validator.validate(validDefinition());
        assertThat(errors).isEmpty();
    }

    @Test
    void topLevelUnknownKey_rejectedWithPointer() {
        String json = validDefinition().replace("\"name\": \"Maple Street Rentals\",", "\"name\": \"Maple Street Rentals\", \"invoices\": [],");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.path().equals("/invoices") && e.message().equals("unknown property"));
    }

    @Test
    void unknownPropertyIsRejectedWithoutStackTrace() {
        String json = validDefinition().replace("\"name\": \"Maple Street Rentals\",", "\"name\": \"Maple Street Rentals\", \"bogusProperty\": 123,");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().equals("unknown property") && !e.message().contains("Exception"));
    }

    @Test
    void badSlugIsRejected() {
        String json = validDefinition().replace("\"id\": \"tenant\"", "\"id\": \"TenantBadSlug\"");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.path().contains("tenant") || e.message().equals("value does not match required format"));
    }

    @Test
    void duplicateFieldIdIsRejected() {
        String json = """
                {
                  "schemaVersion": 1,
                  "template": "test-app",
                  "name": "Test App",
                  "currency": "USD",
                  "timezone": "UTC",
                  "partyTypes": [
                    {
                      "id": "tenant",
                      "label": "Tenant",
                      "fields": [
                        { "id": "email", "label": "Email 1", "type": "email", "required": true },
                        { "id": "email", "label": "Email 2", "type": "email", "required": true }
                      ]
                    }
                  ]
                }
                """;
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.message().equals("duplicate identifier"));
    }

    @Test
    void danglingFieldReferenceIsRejected() {
        String json = validDefinition().replace("\"amountField\": \"rent_amount\"", "\"amountField\": \"non_existent_field\"");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.message().equals("dangling reference") || e.message().equals("value not allowed"));
    }

    @Test
    void wrongFieldTypeForMoneyReferenceIsRejected() {
        String json = validDefinition().replace("\"amountField\": \"rent_amount\"", "\"amountField\": \"name\"");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.message().equals("wrong field type for money reference"));
    }

    @Test
    void negativeFixedAmountAndOffsetOutOfRangeRejected() {
        String json = """
                {
                  "schemaVersion": 1,
                  "template": "test-app",
                  "name": "Test App",
                  "currency": "USD",
                  "timezone": "UTC",
                  "partyTypes": [
                    {
                      "id": "tenant",
                      "label": "Tenant",
                      "fields": [
                        { "id": "name", "label": "Name", "type": "text", "required": true }
                      ]
                    }
                  ],
                  "chargeSchedules": [
                    {
                      "id": "late_fee",
                      "label": "Late Fee",
                      "kind": "late_fee",
                      "partyType": "tenant",
                      "amountSource": "fixed",
                      "amountCents": -500,
                      "graceDays": 10,
                      "enabled": true
                    }
                  ],
                  "reminderRules": [
                    {
                      "id": "reminder1",
                      "label": "Rule",
                      "partyType": "tenant",
                      "trigger": "charge.due",
                      "offsetDays": 400,
                      "recipient": "party",
                      "messageKey": "charge_reminder_late",
                      "channel": "email",
                      "enabled": true
                    }
                  ]
                }
                """;
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.message().equals("value out of range"));
    }

    @Test
    void labelWithAngleBracketsRejected() {
        String json = validDefinition().replace("\"Maple Street Rentals\"", "\"<script>alert(1)</script>\"");
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.path().contains("name") && e.message().equals("value does not match required format"));
    }

    @Test
    void tooManyFieldsRejected() {
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < 35; i++) {
            if (i > 0) fields.append(",");
            fields.append(String.format("{\"id\": \"field_%d\", \"label\": \"Field %d\", \"type\": \"text\", \"required\": false}", i, i));
        }
        String json = String.format("""
                {
                  "schemaVersion": 1,
                  "template": "test-app",
                  "name": "Test App",
                  "currency": "USD",
                  "timezone": "UTC",
                  "partyTypes": [
                    {
                      "id": "tenant",
                      "label": "Tenant",
                      "fields": [%s]
                    }
                  ]
                }
                """, fields);
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).anyMatch(e -> e.message().equals("too many items"));
    }

    @Test
    void oversizedJsonRejectedBeforeParsing() {
        byte[] big = new byte[65537];
        java.util.Arrays.fill(big, (byte) ' ');
        big[0] = '{';
        big[big.length - 1] = '}';
        List<ValidationError> errors = validator.validate(big);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).isEqualTo("oversized JSON");
    }

    @Test
    void duplicateJsonKeyRejected() {
        String json = """
                {
                  "schemaVersion": 1,
                  "schemaVersion": 1,
                  "template": "test-app",
                  "name": "Test App",
                  "currency": "USD",
                  "timezone": "UTC",
                  "partyTypes": [
                    { "id": "t", "label": "T", "fields": [{ "id": "f", "label": "F", "type": "text", "required": false }] }
                  ]
                }
                """;
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).isEqualTo("duplicate JSON key");
    }

    @Test
    void nestingDepthExceededRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            sb.append("{\"a\":");
        }
        sb.append("1");
        for (int i = 0; i < 25; i++) {
            sb.append("}");
        }
        List<ValidationError> errors = validator.validate(sb.toString());
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).isEqualTo("maximum nesting depth exceeded");
    }

    @Test
    void errorListCappedAt20Items() {
        StringBuilder badFields = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) badFields.append(",");
            badFields.append(String.format("{\"id\": \"BAD_%d\", \"label\": \"<bad>\", \"type\": \"unknown_type\", \"required\": true}", i));
        }
        String json = String.format("""
                {
                  "schemaVersion": 1,
                  "template": "test-app",
                  "name": "Test App",
                  "currency": "USD",
                  "timezone": "UTC",
                  "partyTypes": [
                    {
                      "id": "tenant",
                      "label": "Tenant",
                      "fields": [%s]
                    }
                  ]
                }
                """, badFields);
        List<ValidationError> errors = validator.validate(json);
        assertThat(errors.size()).isLessThanOrEqualTo(20);
    }

    @Test
    void labelWith2000CharsAndControlCharDoesNotLeakInErrors() {
        String hugeOddLabel = "A".repeat(2000) + "@#$%^&*";
        String controlCharLabel = "Hello\u0000World";

        String json = validDefinition().replace("\"Maple Street Rentals\"", "\"" + hugeOddLabel + "\"");
        List<ValidationError> errors1 = validator.validate(json);
        assertThat(errors1).isNotEmpty();
        for (ValidationError err : errors1) {
            assertThat(err.message()).doesNotContain("AAAA");
            assertThat(err.message()).doesNotContain("@#$%");
            assertThat(err.path().length()).isLessThanOrEqualTo(200);
        }

        String json2 = validDefinition().replace("\"Maple Street Rentals\"", "\"" + controlCharLabel + "\"");
        List<ValidationError> errors2 = validator.validate(json2);
        assertThat(errors2).isNotEmpty();
        for (ValidationError err : errors2) {
            assertThat(err.message()).doesNotContain("Hello");
            assertThat(err.message()).doesNotContain("\u0000");
        }
    }

    @Test
    void bothTemplatesValidateCleanly() throws Exception {
        String rpmJson = new String(
                getClass().getResourceAsStream("/appdefinition/templates/rental-property-manager.json").readAllBytes(),
                StandardCharsets.UTF_8
        );
        List<ValidationError> rpmErrors = validator.validate(rpmJson);
        assertThat(rpmErrors).isEmpty();

        String cdtJson = new String(
                getClass().getResourceAsStream("/appdefinition/templates/client-document-tracker.json").readAllBytes(),
                StandardCharsets.UTF_8
        );
        List<ValidationError> cdtErrors = validator.validate(cdtJson);
        assertThat(cdtErrors).isEmpty();
    }

    @Test
    void amendmentFMessageKeyTable_violationsRejected() {
        // wrong recipient for charge.due
        String json1 = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "reminderRules": [{
                    "id": "r1", "label": "R1", "partyType": "tenant", "trigger": "charge.due",
                    "offsetDays": -3, "recipient": "owner", "messageKey": "charge_reminder_before", "channel": "email", "enabled": true
                  }]
                }
                """;
        assertThat(validator.validate(json1)).anyMatch(e -> e.path().equals("/reminderRules/0/messageKey"));

        // wrong offset sign (offsetDays: 3 for before)
        String json2 = json1.replace("\"recipient\": \"owner\"", "\"recipient\": \"party\"").replace("\"offsetDays\": -3", "\"offsetDays\": 3");
        assertThat(validator.validate(json2)).anyMatch(e -> e.path().equals("/reminderRules/0/messageKey"));

        // charge.overdue owner offset out of range (> 90)
        String json3 = json1.replace("\"trigger\": \"charge.due\"", "\"trigger\": \"charge.overdue\"")
                .replace("\"messageKey\": \"charge_reminder_before\"", "\"messageKey\": \"charge_overdue_owner\"")
                .replace("\"offsetDays\": -3", "\"offsetDays\": 91");
        assertThat(validator.validate(json3)).anyMatch(e -> e.path().equals("/reminderRules/0/messageKey"));

        // lease.end owner offset positive (> 0)
        String json4 = json1.replace("\"trigger\": \"charge.due\"", "\"trigger\": \"lease.end\"")
                .replace("\"messageKey\": \"charge_reminder_before\"", "\"messageKey\": \"lease_ending_owner\"")
                .replace("\"offsetDays\": -3", "\"offsetDays\": 10")
                .replace("\"fields\": [{\"id\": \"name\", \"label\": \"Name\", \"type\": \"text\", \"required\": true}]",
                        "\"fields\": [{\"id\": \"name\", \"label\": \"Name\", \"type\": \"text\", \"required\": true}, {\"id\": \"lend\", \"label\": \"End\", \"type\": \"date\", \"required\": true}]")
                .replace("\"enabled\": true", "\"enabled\": true, \"dateField\": \"lend\"");
        assertThat(validator.validate(json4)).anyMatch(e -> e.path().equals("/reminderRules/0/messageKey"));
    }

    @Test
    void invalidTimezoneAndCurrency_rejected() {
        for (String badTz : List.of("Mars/Base", "+05:00", "UTC+5")) {
            String json = validDefinition().replace("\"timezone\": \"America/New_York\"", "\"timezone\": \"" + badTz + "\"");
            List<ValidationError> errors = validator.validate(json);
            assertThat(errors).anyMatch(e -> e.path().equals("/timezone") && e.message().equals("value not allowed"));
        }

        String jsonEur = validDefinition().replace("\"currency\": \"USD\"", "\"currency\": \"EUR\"");
        List<ValidationError> errorsEur = validator.validate(jsonEur);
        assertThat(errorsEur).anyMatch(e -> e.path().equals("/currency") && e.message().equals("value not allowed"));
    }

    @Test
    void widgetDataDependenciesAndDateField_validated() {
        // charge widget without chargeSchedule
        String jsonChargeWidget = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "widgets": [{"id": "w1", "type": "sum_expected_period", "partyType": "tenant"}]
                }
                """;
        assertThat(validator.validate(jsonChargeWidget)).anyMatch(e -> e.path().equals("/widgets/0/type") && e.message().contains("charge widget requires charge schedules"));

        // doc widget without documentChecklist
        String jsonDocWidget = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "widgets": [{"id": "w1", "type": "count_overdue_docs", "partyType": "tenant"}]
                }
                """;
        assertThat(validator.validate(jsonDocWidget)).anyMatch(e -> e.path().equals("/widgets/0/type") && e.message().contains("document widget requires document checklists"));

        // dateField on non-expiring-leases widget is rejected
        String jsonDateField = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "chargeSchedules": [{"id": "c1", "label": "C1", "kind": "recurring", "partyType": "tenant", "amountSource": "fixed", "amountCents": 100, "dueDaySource": "fixed", "dueDay": 1, "enabled": true}],
                  "widgets": [{"id": "w1", "type": "sum_expected_period", "partyType": "tenant", "dateField": "some_date"}]
                }
                """;
        assertThat(validator.validate(jsonDateField)).anyMatch(e -> e.path().equals("/widgets/0/dateField") && e.message().contains("not allowed"));
    }

    @Test
    void reminderRuleDateField_validated() {
        // dateField on charge.due is rejected
        String withDateFieldOnChargeDue = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "reminderRules": [{"id": "r1", "label": "R1", "partyType": "tenant", "trigger": "charge.due", "offsetDays": -3, "recipient": "party", "messageKey": "charge_reminder_before", "channel": "email", "enabled": true, "dateField": "some_date"}]
                }
                """;
        assertThat(validator.validate(withDateFieldOnChargeDue)).anyMatch(e -> e.path().equals("/reminderRules/0/dateField") && e.message().contains("not allowed"));

        // lease.end with dateField pointing to non-date field
        String withWrongDateType = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "reminderRules": [{"id": "r1", "label": "R1", "partyType": "tenant", "trigger": "lease.end", "offsetDays": -30, "recipient": "owner", "messageKey": "lease_ending_owner", "channel": "email", "enabled": true, "dateField": "name"}]
                }
                """;
        assertThat(validator.validate(withWrongDateType)).anyMatch(e -> e.path().equals("/reminderRules/0/dateField") && e.message().contains("invalid field reference"));
    }

    @Test
    void chargeScheduleRecurringAndLateFeeRules_validated() {
        // recurring with graceDays rejected
        String recWithGrace = validDefinition().replace("\"enabled\": true", "\"enabled\": true, \"graceDays\": 5");
        assertThat(validator.validate(recWithGrace)).anyMatch(e -> e.path().equals("/chargeSchedules/0/graceDays"));

        // recurring with dueDayField when source is fixed
        String recFixedWithField = validDefinition().replace("\"dueDay\": 1", "\"dueDay\": 1, \"dueDayField\": \"rent_amount\"");
        assertThat(validator.validate(recFixedWithField)).anyMatch(e -> e.path().equals("/chargeSchedules/0/dueDayField"));

        // late_fee with dueDay rejected
        String lateFeeWithDueDay = validDefinition().replace("\"kind\": \"recurring\"", "\"kind\": \"late_fee\"")
                .replace("\"amountSource\": \"field\"", "\"amountSource\": \"fixed\", \"amountCents\": 5000, \"graceDays\": 5")
                .replace("\"amountField\": \"rent_amount\",", "");
        assertThat(validator.validate(lateFeeWithDueDay)).anyMatch(e -> e.path().startsWith("/chargeSchedules/0/dueDay"));

        // late_fee without graceDays rejected
        String lateFeeNoGrace = validDefinition().replace("\"kind\": \"recurring\"", "\"kind\": \"late_fee\"")
                .replace("\"amountSource\": \"field\"", "\"amountSource\": \"fixed\", \"amountCents\": 5000")
                .replace("\"amountField\": \"rent_amount\",", "")
                .replace(",\n                      \"dueDaySource\": \"fixed\",\n                      \"dueDay\": 1", "");
        assertThat(validator.validate(lateFeeNoGrace)).anyMatch(e -> e.path().equals("/chargeSchedules/0/graceDays"));
    }

    @Test
    void danglingReferences_rejected() {
        // record_ref target dangling
        String jsonRecordRef = validDefinition().replace("\"type\": \"text\"",
                "\"type\": \"record_ref\", \"target\": \"missing_unit\"");
        assertThat(validator.validate(jsonRecordRef)).anyMatch(e -> e.path().contains("target") && e.message().equals("dangling reference"));

        // party_ref target dangling
        String jsonPartyRef = validDefinition().replace("\"type\": \"text\"",
                "\"type\": \"party_ref\", \"target\": \"missing_party\"");
        assertThat(validator.validate(jsonPartyRef)).anyMatch(e -> e.path().contains("target") && e.message().equals("dangling reference"));

        // chargeSchedule partyType dangling
        String jsonSchedParty = validDefinition().replace("\"partyType\": \"tenant\"", "\"partyType\": \"missing_party\"");
        assertThat(validator.validate(jsonSchedParty)).anyMatch(e -> e.path().equals("/chargeSchedules/0/partyType") && e.message().equals("dangling reference"));

        // reminderRule partyType dangling
        String jsonReminderParty = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "reminderRules": [{"id": "r1", "label": "R1", "partyType": "missing_party", "trigger": "charge.due", "offsetDays": -3, "recipient": "party", "messageKey": "charge_reminder_before", "channel": "email", "enabled": true}]
                }
                """;
        assertThat(validator.validate(jsonReminderParty)).anyMatch(e -> e.path().equals("/reminderRules/0/partyType") && e.message().equals("dangling reference"));

        // documentChecklist partyType dangling
        String jsonDocParty = """
                {
                  "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                  "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                  "documentChecklists": [{"id": "c1", "label": "C1", "partyType": "missing_party", "items": [{"id": "i1", "label": "I1", "required": true}]}]
                }
                """;
        assertThat(validator.validate(jsonDocParty)).anyMatch(e -> e.path().equals("/documentChecklists/0/partyType") && e.message().equals("dangling reference"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"partyTypes", "chargeSchedules", "reminderRules", "widgets", "selectOptions", "documentItems"})
    void countLimits_limitPassesAndLimitPlusOneRejected(String category) {
        int limit = getLimitForCategory(category);

        String jsonLimit = buildJsonWithCount(category, limit);
        List<ValidationError> errorsLimit = validator.validate(jsonLimit);
        assertThat(errorsLimit).as("Limit of %d for %s should pass", limit, category).isEmpty();

        String jsonExceeded = buildJsonWithCount(category, limit + 1);
        List<ValidationError> errorsExceeded = validator.validate(jsonExceeded);
        assertThat(errorsExceeded).as("Limit+1 (%d) for %s should be rejected", limit + 1, category).isNotEmpty();
        assertThat(errorsExceeded).anyMatch(e -> e.message().equals("too many items"));
    }

    @Test
    void malformedJson_secretMarkerNotLeaked() {
        String malformed = "{ \"secret\": SECRET_MARKER_123, \"unclosed\": ";
        List<ValidationError> errors = validator.validate(malformed);
        assertThat(errors).isNotEmpty();
        for (ValidationError err : errors) {
            assertThat(err.message()).doesNotContain("SECRET_MARKER_123");
            assertThat(err.path()).doesNotContain("SECRET_MARKER_123");
        }
        assertThat(errors).allMatch(e -> e.message().equals("invalid JSON syntax"));
    }

    private int getLimitForCategory(String category) {
        return switch (category) {
            case "partyTypes" -> 5;
            case "chargeSchedules" -> 10;
            case "reminderRules" -> 20;
            case "widgets" -> 10;
            case "selectOptions" -> 50;
            case "documentItems" -> 20;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    private String buildJsonWithCount(String category, int count) {
        if ("partyTypes".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"party_%d\", \"label\": \"Party %d\", \"fields\": [{\"id\": \"name\", \"label\": \"Name\", \"type\": \"text\", \"required\": true}]}", i, i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [%s]
                    }
                    """, sb);
        }
        if ("chargeSchedules".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"sched_%d\", \"label\": \"Sched %d\", \"kind\": \"recurring\", \"partyType\": \"tenant\", \"amountSource\": \"field\", \"amountField\": \"rent\", \"dueDaySource\": \"fixed\", \"dueDay\": 1, \"enabled\": true}", i, i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "rent", "label": "Rent", "type": "money", "required": true}]}],
                      "chargeSchedules": [%s]
                    }
                    """, sb);
        }
        if ("reminderRules".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"rule_%d\", \"label\": \"Rule %d\", \"partyType\": \"tenant\", \"trigger\": \"charge.due\", \"offsetDays\": -3, \"recipient\": \"party\", \"messageKey\": \"charge_reminder_before\", \"channel\": \"email\", \"enabled\": true}", i, i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "rent", "label": "Rent", "type": "money", "required": true}]}],
                      "chargeSchedules": [{"id": "rent_sched", "label": "Rent", "kind": "recurring", "partyType": "tenant", "amountSource": "field", "amountField": "rent", "dueDaySource": "fixed", "dueDay\": 1, \"enabled\": true}],
                      "reminderRules": [%s]
                    }
                    """, sb);
        }
        if ("widgets".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"widget_%d\", \"type\": \"sum_expected_period\", \"partyType\": \"tenant\"}", i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [{"id": "tenant", "label": "Tenant", "fields": [{"id": "rent", "label": "Rent", "type": "money", "required": true}]}],
                      "chargeSchedules": [{"id": "rent_sched", "label": "Rent", "kind": "recurring", "partyType": "tenant", "amountSource": "field", "amountField": "rent", "dueDaySource": "fixed", "dueDay\": 1, \"enabled\": true}],
                      "widgets": [%s]
                    }
                    """, sb);
        }
        if ("selectOptions".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"opt_%d\", \"label\": \"Opt %d\"}", i, i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [{
                        "id": "tenant", "label": "Tenant",
                        "fields": [{
                          "id": "status", "label": "Status", "type": "select", "required": true,
                          "options": [%s]
                        }]
                      }]
                    }
                    """, sb);
        }
        if ("documentItems".equals(category)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"item_%d\", \"label\": \"Item %d\", \"required\": true}", i, i));
            }
            return String.format("""
                    {
                      "schemaVersion": 1, "template": "test-app", "name": "Test App", "currency": "USD", "timezone": "America/New_York",
                      "partyTypes": [{"id": "client", "label": "Client", "fields": [{"id": "name", "label": "Name", "type": "text", "required": true}]}],
                      "documentChecklists": [{
                        "id": "docs", "label": "Docs", "partyType": "client",
                        "items": [%s]
                      }]
                    }
                    """, sb);
        }
        throw new IllegalArgumentException("Unknown category: " + category);
    }
}
