package com.followwise.appdef;

import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.*;

public class SemanticValidator {

    private static final Set<String> CHARGE_WIDGET_TYPES = Set.of(
            "count_overdue_charges",
            "sum_overdue_charges",
            "sum_expected_period",
            "sum_collected_period"
    );

    private static final Set<String> DOC_WIDGET_TYPES = Set.of(
            "count_overdue_docs",
            "list_missing_docs",
            "list_expiring_docs",
            "completion_pct_by_party"
    );

    public List<ValidationError> validate(JsonNode root) {
        List<ValidationError> errors = new ArrayList<>();

        if (root == null || !root.isObject()) {
            errors.add(new ValidationError("#", "root must be an object"));
            return errors;
        }

        validateTimezone(root, errors);

        Map<String, Map<String, String>> partyTypes = new HashMap<>();
        validatePartyTypes(root, partyTypes, errors);

        Map<String, Map<String, String>> recordTypes = new HashMap<>();
        validateRecordTypes(root, recordTypes, errors);

        validateFieldReferences(root, partyTypes, recordTypes, errors);
        validateChargeSchedules(root, partyTypes, errors);
        validateDocumentChecklists(root, partyTypes, errors);
        validateReminderRules(root, partyTypes, errors);
        validateWidgets(root, partyTypes, errors);

        return errors;
    }

    private void validateTimezone(JsonNode root, List<ValidationError> errors) {
        if (root.has("timezone")) {
            String tz = root.get("timezone").asText();
            if (!ZoneId.getAvailableZoneIds().contains(tz)) {
                errors.add(new ValidationError("/timezone", "value not allowed"));
            }
        }
    }

    private void validatePartyTypes(JsonNode root, Map<String, Map<String, String>> partyTypes, List<ValidationError> errors) {
        if (!root.has("partyTypes") || !root.get("partyTypes").isArray()) {
            return;
        }
        JsonNode arr = root.get("partyTypes");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode partyType = arr.get(i);
            if (!partyType.has("id")) continue;
            String id = partyType.get("id").asText();
            String path = "/partyTypes/" + i;
            if (partyTypes.containsKey(id)) {
                errors.add(new ValidationError(path + "/id", "duplicate identifier"));
            }
            Map<String, String> fieldMap = new HashMap<>();
            partyTypes.put(id, fieldMap);

            if (partyType.has("fields") && partyType.get("fields").isArray()) {
                JsonNode fields = partyType.get("fields");
                for (int j = 0; j < fields.size(); j++) {
                    JsonNode field = fields.get(j);
                    if (!field.has("id")) continue;
                    String fieldId = field.get("id").asText();
                    String fieldPath = path + "/fields/" + j;
                    if (fieldMap.containsKey(fieldId)) {
                        errors.add(new ValidationError(fieldPath + "/id", "duplicate identifier"));
                    }
                    String type = field.has("type") ? field.get("type").asText() : "";
                    fieldMap.put(fieldId, type);

                    if ("select".equals(type) && field.has("options") && field.get("options").isArray()) {
                        Set<String> optionIds = new HashSet<>();
                        JsonNode options = field.get("options");
                        for (int k = 0; k < options.size(); k++) {
                            JsonNode opt = options.get(k);
                            if (opt.has("id")) {
                                String optId = opt.get("id").asText();
                                if (!optionIds.add(optId)) {
                                    errors.add(new ValidationError(fieldPath + "/options/" + k + "/id", "duplicate identifier"));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateRecordTypes(JsonNode root, Map<String, Map<String, String>> recordTypes, List<ValidationError> errors) {
        if (!root.has("recordTypes") || !root.get("recordTypes").isArray()) {
            return;
        }
        JsonNode arr = root.get("recordTypes");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode recordType = arr.get(i);
            if (!recordType.has("id")) continue;
            String id = recordType.get("id").asText();
            String path = "/recordTypes/" + i;
            if (recordTypes.containsKey(id)) {
                errors.add(new ValidationError(path + "/id", "duplicate identifier"));
            }
            Map<String, String> fieldMap = new HashMap<>();
            recordTypes.put(id, fieldMap);

            if (recordType.has("fields") && recordType.get("fields").isArray()) {
                JsonNode fields = recordType.get("fields");
                for (int j = 0; j < fields.size(); j++) {
                    JsonNode field = fields.get(j);
                    if (!field.has("id")) continue;
                    String fieldId = field.get("id").asText();
                    String fieldPath = path + "/fields/" + j;
                    if (fieldMap.containsKey(fieldId)) {
                        errors.add(new ValidationError(fieldPath + "/id", "duplicate identifier"));
                    }
                    String type = field.has("type") ? field.get("type").asText() : "";
                    fieldMap.put(fieldId, type);

                    if ("select".equals(type) && field.has("options") && field.get("options").isArray()) {
                        Set<String> optionIds = new HashSet<>();
                        JsonNode options = field.get("options");
                        for (int k = 0; k < options.size(); k++) {
                            JsonNode opt = options.get(k);
                            if (opt.has("id")) {
                                String optId = opt.get("id").asText();
                                if (!optionIds.add(optId)) {
                                    errors.add(new ValidationError(fieldPath + "/options/" + k + "/id", "duplicate identifier"));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateFieldReferences(JsonNode root, Map<String, Map<String, String>> partyTypes, Map<String, Map<String, String>> recordTypes, List<ValidationError> errors) {
        if (root.has("partyTypes") && root.get("partyTypes").isArray()) {
            JsonNode arr = root.get("partyTypes");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode partyType = arr.get(i);
                if (partyType.has("fields") && partyType.get("fields").isArray()) {
                    validateRefsInFields(partyType.get("fields"), "/partyTypes/" + i + "/fields", partyTypes, recordTypes, errors);
                }
            }
        }
        if (root.has("recordTypes") && root.get("recordTypes").isArray()) {
            JsonNode arr = root.get("recordTypes");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode recordType = arr.get(i);
                if (recordType.has("fields") && recordType.get("fields").isArray()) {
                    validateRefsInFields(recordType.get("fields"), "/recordTypes/" + i + "/fields", partyTypes, recordTypes, errors);
                }
            }
        }
    }

    private void validateRefsInFields(JsonNode fields, String basePath, Map<String, Map<String, String>> partyTypes, Map<String, Map<String, String>> recordTypes, List<ValidationError> errors) {
        for (int j = 0; j < fields.size(); j++) {
            JsonNode field = fields.get(j);
            String type = field.has("type") ? field.get("type").asText() : "";
            if ("party_ref".equals(type)) {
                String target = field.has("target") ? field.get("target").asText() : "";
                if (!partyTypes.containsKey(target)) {
                    errors.add(new ValidationError(basePath + "/" + j + "/target", "dangling reference"));
                }
            } else if ("record_ref".equals(type)) {
                String target = field.has("target") ? field.get("target").asText() : "";
                if (!recordTypes.containsKey(target)) {
                    errors.add(new ValidationError(basePath + "/" + j + "/target", "dangling reference"));
                }
            }
        }
    }

    private void validateChargeSchedules(JsonNode root, Map<String, Map<String, String>> partyTypes, List<ValidationError> errors) {
        if (!root.has("chargeSchedules") || !root.get("chargeSchedules").isArray()) {
            return;
        }
        Set<String> scheduleIds = new HashSet<>();
        JsonNode arr = root.get("chargeSchedules");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode sched = arr.get(i);
            String path = "/chargeSchedules/" + i;
            if (sched.has("id")) {
                String id = sched.get("id").asText();
                if (!scheduleIds.add(id)) {
                    errors.add(new ValidationError(path + "/id", "duplicate identifier"));
                }
            }
            String partyTypeId = sched.has("partyType") ? sched.get("partyType").asText() : "";
            if (!partyTypes.containsKey(partyTypeId)) {
                errors.add(new ValidationError(path + "/partyType", "dangling reference"));
                continue;
            }
            Map<String, String> fields = partyTypes.get(partyTypeId);

            String amountSource = sched.has("amountSource") ? sched.get("amountSource").asText() : "";
            if ("field".equals(amountSource)) {
                if (!sched.has("amountField")) {
                    errors.add(new ValidationError(path + "/amountField", "missing required property"));
                } else {
                    String amountField = sched.get("amountField").asText();
                    if (!fields.containsKey(amountField)) {
                        errors.add(new ValidationError(path + "/amountField", "dangling reference"));
                    } else if (!"money".equals(fields.get(amountField))) {
                        errors.add(new ValidationError(path + "/amountField", "wrong field type for money reference"));
                    }
                }
            } else if ("fixed".equals(amountSource)) {
                if (!sched.has("amountCents")) {
                    errors.add(new ValidationError(path + "/amountCents", "missing required property"));
                }
            }

            String kind = sched.has("kind") ? sched.get("kind").asText() : "";
            if ("recurring".equals(kind)) {
                if (sched.has("graceDays")) {
                    errors.add(new ValidationError(path + "/graceDays", "graceDays not allowed on recurring charge schedule"));
                }
                if (!sched.has("dueDaySource")) {
                    errors.add(new ValidationError(path + "/dueDaySource", "missing required property"));
                } else {
                    String dueDaySource = sched.get("dueDaySource").asText();
                    if ("field".equals(dueDaySource)) {
                        if (sched.has("dueDay")) {
                            errors.add(new ValidationError(path + "/dueDay", "dueDay not allowed when dueDaySource is field"));
                        }
                        if (!sched.has("dueDayField")) {
                            errors.add(new ValidationError(path + "/dueDayField", "missing required property"));
                        } else {
                            String dueDayField = sched.get("dueDayField").asText();
                            if (!fields.containsKey(dueDayField)) {
                                errors.add(new ValidationError(path + "/dueDayField", "dangling reference"));
                            } else if (!"integer".equals(fields.get(dueDayField))) {
                                errors.add(new ValidationError(path + "/dueDayField", "invalid field reference"));
                            }
                        }
                    } else if ("fixed".equals(dueDaySource)) {
                        if (sched.has("dueDayField")) {
                            errors.add(new ValidationError(path + "/dueDayField", "dueDayField not allowed when dueDaySource is fixed"));
                        }
                        if (!sched.has("dueDay")) {
                            errors.add(new ValidationError(path + "/dueDay", "missing required property"));
                        }
                    }
                }
            } else if ("late_fee".equals(kind)) {
                if (!sched.has("graceDays")) {
                    errors.add(new ValidationError(path + "/graceDays", "missing required property"));
                }
                if (sched.has("dueDaySource")) {
                    errors.add(new ValidationError(path + "/dueDaySource", "dueDaySource not allowed on late_fee charge schedule"));
                }
                if (sched.has("dueDayField")) {
                    errors.add(new ValidationError(path + "/dueDayField", "dueDayField not allowed on late_fee charge schedule"));
                }
                if (sched.has("dueDay")) {
                    errors.add(new ValidationError(path + "/dueDay", "dueDay not allowed on late_fee charge schedule"));
                }
            }
        }
    }

    private void validateDocumentChecklists(JsonNode root, Map<String, Map<String, String>> partyTypes, List<ValidationError> errors) {
        if (!root.has("documentChecklists") || !root.get("documentChecklists").isArray()) {
            return;
        }
        Set<String> checklistIds = new HashSet<>();
        JsonNode arr = root.get("documentChecklists");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode checklist = arr.get(i);
            String path = "/documentChecklists/" + i;
            if (checklist.has("id")) {
                String id = checklist.get("id").asText();
                if (!checklistIds.add(id)) {
                    errors.add(new ValidationError(path + "/id", "duplicate identifier"));
                }
            }
            String partyTypeId = checklist.has("partyType") ? checklist.get("partyType").asText() : "";
            if (!partyTypes.containsKey(partyTypeId)) {
                errors.add(new ValidationError(path + "/partyType", "dangling reference"));
            }
            if (checklist.has("items") && checklist.get("items").isArray()) {
                Set<String> itemIds = new HashSet<>();
                JsonNode items = checklist.get("items");
                for (int j = 0; j < items.size(); j++) {
                    JsonNode item = items.get(j);
                    if (item.has("id")) {
                        String itemId = item.get("id").asText();
                        if (!itemIds.add(itemId)) {
                            errors.add(new ValidationError(path + "/items/" + j + "/id", "duplicate identifier"));
                        }
                    }
                }
            }
        }
    }

    private void validateReminderRules(JsonNode root, Map<String, Map<String, String>> partyTypes, List<ValidationError> errors) {
        if (!root.has("reminderRules") || !root.get("reminderRules").isArray()) {
            return;
        }
        Set<String> ruleIds = new HashSet<>();
        JsonNode arr = root.get("reminderRules");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode rule = arr.get(i);
            String path = "/reminderRules/" + i;
            if (rule.has("id")) {
                String id = rule.get("id").asText();
                if (!ruleIds.add(id)) {
                    errors.add(new ValidationError(path + "/id", "duplicate identifier"));
                }
            }
            String partyTypeId = rule.has("partyType") ? rule.get("partyType").asText() : "";
            Map<String, String> fields = partyTypes.get(partyTypeId);
            if (fields == null) {
                errors.add(new ValidationError(path + "/partyType", "dangling reference"));
            }

            String trigger = rule.has("trigger") ? rule.get("trigger").asText() : "";
            String recipient = rule.has("recipient") ? rule.get("recipient").asText() : "";
            String messageKey = rule.has("messageKey") ? rule.get("messageKey").asText() : "";
            int offset = rule.has("offsetDays") ? rule.get("offsetDays").asInt() : 0;

            if ("lease.end".equals(trigger)) {
                if (!rule.has("dateField")) {
                    errors.add(new ValidationError(path + "/dateField", "missing required property"));
                } else if (fields != null) {
                    String dateField = rule.get("dateField").asText();
                    if (!fields.containsKey(dateField)) {
                        errors.add(new ValidationError(path + "/dateField", "dangling reference"));
                    } else if (!"date".equals(fields.get(dateField))) {
                        errors.add(new ValidationError(path + "/dateField", "invalid field reference"));
                    }
                }
            } else if (rule.has("dateField")) {
                errors.add(new ValidationError(path + "/dateField", "dateField not allowed on this trigger"));
            }

            if (!isValidMessageKey(trigger, recipient, offset, messageKey)) {
                errors.add(new ValidationError(path + "/messageKey", "message key does not match trigger and recipient"));
            }
        }
    }

    private boolean isValidMessageKey(String trigger, String recipient, int offset, String messageKey) {
        if ("charge.due".equals(trigger) && "party".equals(recipient)) {
            if (offset < 0) return "charge_reminder_before".equals(messageKey);
            if (offset == 0) return "charge_reminder_due".equals(messageKey);
            return "charge_reminder_late".equals(messageKey);
        }
        if ("charge.overdue".equals(trigger) && "owner".equals(recipient)) {
            return offset >= 0 && offset <= 90 && "charge_overdue_owner".equals(messageKey);
        }
        if ("document.due".equals(trigger)) {
            if ("party".equals(recipient)) {
                if (offset < 0) return "doc_reminder_before".equals(messageKey);
                if (offset == 0) return "doc_reminder_due".equals(messageKey);
                return "doc_reminder_late".equals(messageKey);
            }
            if ("owner".equals(recipient)) {
                return offset > 0 && "doc_overdue_owner".equals(messageKey);
            }
        }
        if ("document.expiry".equals(trigger) && "party".equals(recipient)) {
            return offset < 0 && "doc_expiry_reminder".equals(messageKey);
        }
        if ("lease.end".equals(trigger) && "owner".equals(recipient)) {
            return offset < 0 && "lease_ending_owner".equals(messageKey);
        }
        return false;
    }

    private void validateWidgets(JsonNode root, Map<String, Map<String, String>> partyTypes, List<ValidationError> errors) {
        if (!root.has("widgets") || !root.get("widgets").isArray()) {
            return;
        }
        boolean hasChargeSchedules = root.has("chargeSchedules") && root.get("chargeSchedules").isArray() && !root.get("chargeSchedules").isEmpty();
        boolean hasDocChecklists = root.has("documentChecklists") && root.get("documentChecklists").isArray() && !root.get("documentChecklists").isEmpty();

        Set<String> widgetIds = new HashSet<>();
        JsonNode arr = root.get("widgets");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode widget = arr.get(i);
            String path = "/widgets/" + i;
            if (widget.has("id")) {
                String id = widget.get("id").asText();
                if (!widgetIds.add(id)) {
                    errors.add(new ValidationError(path + "/id", "duplicate identifier"));
                }
            }
            String type = widget.has("type") ? widget.get("type").asText() : "";
            if (CHARGE_WIDGET_TYPES.contains(type) && !hasChargeSchedules) {
                errors.add(new ValidationError(path + "/type", "charge widget requires charge schedules"));
            }
            if (DOC_WIDGET_TYPES.contains(type) && !hasDocChecklists) {
                errors.add(new ValidationError(path + "/type", "document widget requires document checklists"));
            }

            if ("list_expiring_leases".equals(type)) {
                if (!widget.has("partyType")) {
                    errors.add(new ValidationError(path + "/partyType", "missing required property"));
                } else {
                    String partyTypeId = widget.get("partyType").asText();
                    if (!partyTypes.containsKey(partyTypeId)) {
                        errors.add(new ValidationError(path + "/partyType", "dangling reference"));
                    } else {
                        Map<String, String> fields = partyTypes.get(partyTypeId);
                        if (!widget.has("dateField")) {
                            errors.add(new ValidationError(path + "/dateField", "missing required property"));
                        } else {
                            String dateField = widget.get("dateField").asText();
                            if (!fields.containsKey(dateField)) {
                                errors.add(new ValidationError(path + "/dateField", "dangling reference"));
                            } else if (!"date".equals(fields.get(dateField))) {
                                errors.add(new ValidationError(path + "/dateField", "invalid field reference"));
                            }
                        }
                    }
                }
            } else {
                if (widget.has("dateField")) {
                    errors.add(new ValidationError(path + "/dateField", "dateField not allowed on this widget type"));
                }
                if (widget.has("partyType")) {
                    String partyTypeId = widget.get("partyType").asText();
                    if (!partyTypes.containsKey(partyTypeId)) {
                        errors.add(new ValidationError(path + "/partyType", "dangling reference"));
                    }
                }
            }
        }
    }
}
