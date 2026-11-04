package com.followwise.appdef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionValidatorTest {

    private TransitionValidator transitionValidator;

    private static final String BASE_VALID_DEF = """
            {
              "schemaVersion": 1,
              "template": "rental-property-manager",
              "name": "Rental Property Manager",
              "currency": "USD",
              "timezone": "America/New_York",
              "partyTypes": [
                {
                  "id": "tenant",
                  "label": "Tenant",
                  "archived": false,
                  "fields": [
                    { "id": "name", "label": "Full Name", "type": "text", "required": true },
                    { "id": "unit", "label": "Assigned Unit", "type": "record_ref", "target": "unit" },
                    {
                      "id": "lease_type",
                      "label": "Lease Type",
                      "type": "select",
                      "options": [
                        { "id": "fixed", "label": "Fixed Term" },
                        { "id": "month_to_month", "label": "Month to Month" }
                      ]
                    }
                  ]
                }
              ],
              "recordTypes": [
                {
                  "id": "unit",
                  "label": "Unit",
                  "fields": [
                    { "id": "unit_number", "label": "Unit #", "type": "text", "required": true }
                  ]
                }
              ],
              "chargeSchedules": [
                {
                  "id": "rent",
                  "label": "Monthly Rent",
                  "kind": "recurring",
                  "partyType": "tenant",
                  "amountField": "monthly_rent",
                  "dueDayField": "due_day",
                  "enabled": true
                }
              ],
              "reminderRules": [
                {
                  "id": "rent_due",
                  "label": "Rent Due Reminder",
                  "partyType": "tenant",
                  "trigger": "charge.due",
                  "daysOffset": -3,
                  "channel": "email",
                  "recipient": "party",
                  "messageKey": "charge.due.party",
                  "enabled": true
                }
              ],
              "documentChecklists": [
                {
                  "id": "lease_docs",
                  "label": "Lease Documents",
                  "partyType": "tenant",
                  "items": [
                    { "id": "lease_agreement", "label": "Signed Lease Agreement", "required": true }
                  ]
                }
              ],
              "widgets": [
                {
                  "id": "rent_expected",
                  "label": "Expected Rent",
                  "type": "sum_expected_period"
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        transitionValidator = new TransitionValidator();
    }

    @Test
    void identicalDefinitions_allowed() {
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, BASE_VALID_DEF);
        assertThat(errors).isEmpty();
    }

    @Test
    void addingField_allowed() {
        String newDef = BASE_VALID_DEF.replace(
                "\"fields\": [",
                "\"fields\": [\n        { \"id\": \"phone\", \"label\": \"Phone Number\", \"type\": \"phone\" },"
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isEmpty();
    }

    @Test
    void removingField_rejected() {
        String newDef = BASE_VALID_DEF.replace(
                "{ \"id\": \"name\", \"label\": \"Full Name\", \"type\": \"text\", \"required\": true },",
                ""
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("field cannot be removed"));
    }

    @Test
    void removingPartyType_rejected() {
        String newDef = BASE_VALID_DEF.replace("\"id\": \"tenant\"", "\"id\": \"tenant_replaced\"");

        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("party type cannot be removed"));
    }

    @Test
    void changingFieldType_rejected() {
        String newDef = BASE_VALID_DEF.replace(
                "\"type\": \"text\", \"required\": true",
                "\"type\": \"email\", \"required\": true"
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("field type cannot be changed"));
    }

    @Test
    void removingSelectOption_rejected() {
        String newDef = BASE_VALID_DEF.replace(
                "{ \"id\": \"fixed\", \"label\": \"Fixed Term\" },",
                ""
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("select option cannot be removed"));
    }

    @Test
    void addingSelectOption_allowed() {
        String newDef = BASE_VALID_DEF.replace(
                "{ \"id\": \"month_to_month\", \"label\": \"Month to Month\" }",
                "{ \"id\": \"month_to_month\", \"label\": \"Month to Month\" }, { \"id\": \"annual\", \"label\": \"Annual\" }"
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isEmpty();
    }

    @Test
    void archivingAndUnarchivingPartyType_allowed() {
        String archivedDef = BASE_VALID_DEF.replace("\"archived\": false", "\"archived\": true");
        List<ValidationError> errorsArchived = transitionValidator.validateTransition(BASE_VALID_DEF, archivedDef);
        assertThat(errorsArchived).isEmpty();

        List<ValidationError> errorsUnarchived = transitionValidator.validateTransition(archivedDef, BASE_VALID_DEF);
        assertThat(errorsUnarchived).isEmpty();
    }

    @Test
    void relabeling_allowed() {
        String newDef = BASE_VALID_DEF
                .replace("\"label\": \"Tenant\"", "\"label\": \"Resident\"")
                .replace("\"label\": \"Full Name\"", "\"label\": \"Legal Name\"");
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isEmpty();
    }

    @Test
    void removingChargeSchedule_rejected() {
        String newDef = BASE_VALID_DEF.replace("\"id\": \"rent\"", "\"id\": \"rent_replaced\"");
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("charge schedule cannot be removed"));
    }

    @Test
    void changingReminderTrigger_rejected() {
        String newDef = BASE_VALID_DEF.replace(
                "\"trigger\": \"charge.due\"",
                "\"trigger\": \"charge.overdue\""
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("reminder rule trigger cannot be changed"));
    }

    @Test
    void changingReferenceTarget_rejected() {
        String newDef = BASE_VALID_DEF.replace(
                "\"target\": \"unit\"",
                "\"target\": \"building\""
        );
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.message().contains("reference target cannot be changed"));
    }

    @Test
    void changingCurrency_rejected() {
        String newDef = BASE_VALID_DEF.replace("\"currency\": \"USD\"", "\"currency\": \"EUR\"");
        List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, newDef);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.path().equals("/currency") && e.message().contains("currency cannot be changed"));
    }

    @Test
    void changingTimezone_validAllowed_invalidRejected() {
        String validTzDef = BASE_VALID_DEF.replace("\"timezone\": \"America/New_York\"", "\"timezone\": \"Europe/London\"");
        List<ValidationError> validErrors = transitionValidator.validateTransition(BASE_VALID_DEF, validTzDef);
        assertThat(validErrors).isEmpty();

        String invalidTzDef = BASE_VALID_DEF.replace("\"timezone\": \"America/New_York\"", "\"timezone\": \"Mars/Base\"");
        List<ValidationError> invalidErrors = transitionValidator.validateTransition(BASE_VALID_DEF, invalidTzDef);
        assertThat(invalidErrors).isNotEmpty();
        assertThat(invalidErrors).anyMatch(e -> e.path().equals("/timezone"));
    }

    @Test
    void changingTimezone_offsetsRejected() {
        for (String badTz : List.of("+05:00", "UTC+5")) {
            String badDef = BASE_VALID_DEF.replace("\"timezone\": \"America/New_York\"", "\"timezone\": \"" + badTz + "\"");
            List<ValidationError> errors = transitionValidator.validateTransition(BASE_VALID_DEF, badDef);
            assertThat(errors).isNotEmpty();
            assertThat(errors).anyMatch(e -> e.path().equals("/timezone") && e.message().equals("value not allowed"));
        }
    }
}
