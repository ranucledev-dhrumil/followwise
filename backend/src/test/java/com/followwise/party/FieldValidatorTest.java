package com.followwise.party;

import com.followwise.appdef.ValidationError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FieldValidatorTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private FieldValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FieldValidator();
    }

    private JsonNode createAllTypesDefinition() throws Exception {
        String json = """
                {
                  "id": "custom_type",
                  "label": "Custom Type",
                  "archived": false,
                  "fields": [
                    { "id": "t_text", "label": "Text", "type": "text", "required": true },
                    { "id": "t_email", "label": "Email", "type": "email", "required": false },
                    { "id": "t_phone", "label": "Phone", "type": "phone", "required": false },
                    { "id": "t_date", "label": "Date", "type": "date", "required": false },
                    { "id": "t_int", "label": "Int", "type": "integer", "required": false },
                    { "id": "t_money", "label": "Money", "type": "money", "required": false },
                    { "id": "t_bool", "label": "Bool", "type": "boolean", "required": false },
                    {
                      "id": "t_select",
                      "label": "Select",
                      "type": "select",
                      "required": false,
                      "options": [
                        { "id": "opt_active", "label": "Active", "archived": false },
                        { "id": "opt_archived", "label": "Archived", "archived": true }
                      ]
                    },
                    { "id": "t_party_ref", "label": "Party Ref", "type": "party_ref", "target": "other_party", "required": false },
                    { "id": "t_record_ref", "label": "Record Ref", "type": "record_ref", "target": "other_record", "required": false },
                    { "id": "t_archived_field", "label": "Archived Field", "type": "text", "archived": true, "required": false }
                  ]
                }
                """;
        return jsonMapper.readTree(json);
    }

    @Test
    void allTenTypes_validSubmission() throws Exception {
        JsonNode typeNode = createAllTypesDefinition();
        UUID pId = UUID.randomUUID();
        UUID rId = UUID.randomUUID();

        RefChecker refChecker = new RefChecker() {
            @Override
            public TargetStatus checkPartyRef(UUID targetId, String targetType) {
                return targetId.equals(pId) && "other_party".equals(targetType) ? TargetStatus.VALID : TargetStatus.NOT_FOUND;
            }

            @Override
            public TargetStatus checkRecordRef(UUID targetId, String targetType) {
                return targetId.equals(rId) && "other_record".equals(targetType) ? TargetStatus.VALID : TargetStatus.NOT_FOUND;
            }
        };

        ObjectNode data = jsonMapper.createObjectNode()
                .put("t_text", "Sample Text")
                .put("t_email", "Hello@Example.COM")
                .put("t_phone", "+1 555-123-4567")
                .put("t_date", "2024-02-29")
                .put("t_int", 1000000000L)
                .put("t_money", 100000000000L)
                .put("t_bool", true)
                .put("t_select", "opt_active")
                .put("t_party_ref", pId.toString())
                .put("t_record_ref", rId.toString());

        FieldValidationResult res = validator.validate(typeNode, data, null, refChecker);
        assertTrue(res.isValid(), () -> "Expected valid but got errors: " + res.errors());
        assertEquals("Sample Text", res.computedDisplayName());
        assertEquals("hello@example.com", res.normalizedData().get("t_email").asText());
    }

    @Test
    void integerAndMoneyBounds() throws Exception {
        JsonNode typeNode = createAllTypesDefinition();

        // Integer out of bounds: 1_000_000_001
        ObjectNode data = jsonMapper.createObjectNode()
                .put("t_text", "Sample")
                .put("t_int", 1_000_000_001L);

        FieldValidationResult res = validator.validate(typeNode, data, null, null);
        assertFalse(res.isValid());
        assertEquals("/data/t_int", res.errors().get(0).path());

        // Integer out of bounds: -1_000_000_001
        data.put("t_int", -1_000_000_001L);
        res = validator.validate(typeNode, data, null, null);
        assertFalse(res.isValid());

        // Money out of bounds: 100_000_000_001L
        data.remove("t_int");
        data.put("t_money", 100_000_000_001L);
        res = validator.validate(typeNode, data, null, null);
        assertFalse(res.isValid());
        assertEquals("/data/t_money", res.errors().get(0).path());

        // Money negative
        data.put("t_money", -1L);
        res = validator.validate(typeNode, data, null, null);
        assertFalse(res.isValid());
    }

    @Test
    void archivedField_readOnlyRules() throws Exception {
        JsonNode typeNode = createAllTypesDefinition();

        // 1. Create with archived field submitted -> rejected
        ObjectNode createData = jsonMapper.createObjectNode()
                .put("t_text", "Sample")
                .put("t_archived_field", "Forbidden");

        FieldValidationResult res = validator.validate(typeNode, createData, null, null);
        assertFalse(res.isValid());
        assertEquals("/data/t_archived_field", res.errors().get(0).path());

        // 2. Update without archived field submitted -> carried over from previousData
        ObjectNode prevData = jsonMapper.createObjectNode()
                .put("t_text", "Sample")
                .put("t_archived_field", "StoredValue");

        ObjectNode updateData = jsonMapper.createObjectNode()
                .put("t_text", "Sample Updated");

        res = validator.validate(typeNode, updateData, prevData, null);
        assertTrue(res.isValid());
        assertEquals("StoredValue", res.normalizedData().get("t_archived_field").asText());

        // 3. Update with different archived field value submitted -> rejected
        updateData.put("t_archived_field", "DifferentValue");
        res = validator.validate(typeNode, updateData, prevData, null);
        assertFalse(res.isValid());
        assertEquals("/data/t_archived_field", res.errors().get(0).path());
    }

    @Test
    void archivedOption_rejected() throws Exception {
        JsonNode typeNode = createAllTypesDefinition();
        ObjectNode data = jsonMapper.createObjectNode()
                .put("t_text", "Sample")
                .put("t_select", "opt_archived");

        FieldValidationResult res = validator.validate(typeNode, data, null, null);
        assertFalse(res.isValid());
        assertEquals("/data/t_select", res.errors().get(0).path());
        assertEquals("invalid select option", res.errors().get(0).message());
    }
}
