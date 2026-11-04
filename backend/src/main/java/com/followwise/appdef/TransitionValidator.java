package com.followwise.appdef;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneId;
import java.util.*;

@Component
public class TransitionValidator {

    private static final int MAX_ERRORS = 20;
    private final JsonMapper jsonMapper;

    public TransitionValidator() {
        this.jsonMapper = new JsonMapper();
    }

    public TransitionValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper != null ? jsonMapper : new JsonMapper();
    }

    public List<ValidationError> validateTransition(String oldJson, String newJson) {
        try {
            JsonNode oldDef = jsonMapper.readTree(oldJson);
            JsonNode newDef = jsonMapper.readTree(newJson);
            return validateTransition(oldDef, newDef);
        } catch (Exception e) {
            return List.of(new ValidationError("#", "invalid JSON syntax"));
        }
    }

    public List<ValidationError> validateTransition(JsonNode oldDef, JsonNode newDef) {
        List<ValidationError> errors = new ArrayList<>();

        if (oldDef == null || newDef == null || !oldDef.isObject() || !newDef.isObject()) {
            errors.add(new ValidationError("#", "root must be an object"));
            return errors;
        }

        validateCurrency(oldDef, newDef, errors);
        validateTimezone(newDef, errors);
        validatePartyTypes(oldDef, newDef, errors);
        validateRecordTypes(oldDef, newDef, errors);
        validateChargeSchedules(oldDef, newDef, errors);
        validateReminderRules(oldDef, newDef, errors);
        validateDocumentChecklists(oldDef, newDef, errors);
        validateWidgets(oldDef, newDef, errors);

        if (errors.size() > MAX_ERRORS) {
            return errors.subList(0, MAX_ERRORS);
        }
        return errors;
    }

    private void validateCurrency(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (oldDef.has("currency") && newDef.has("currency")) {
            String oldCur = oldDef.get("currency").asText();
            String newCur = newDef.get("currency").asText();
            if (!Objects.equals(oldCur, newCur)) {
                errors.add(new ValidationError("/currency", "currency cannot be changed"));
            }
        }
    }

    private void validateTimezone(JsonNode newDef, List<ValidationError> errors) {
        if (newDef.has("timezone")) {
            String tz = newDef.get("timezone").asText();
            if (!ZoneId.getAvailableZoneIds().contains(tz)) {
                errors.add(new ValidationError("/timezone", "value not allowed"));
            }
        }
    }

    private void validatePartyTypes(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("partyTypes") || !oldDef.get("partyTypes").isArray()) {
            return;
        }
        Map<String, JsonNode> newPartyMap = new HashMap<>();
        Map<String, Integer> newPartyIndexMap = new HashMap<>();
        if (newDef.has("partyTypes") && newDef.get("partyTypes").isArray()) {
            JsonNode newArr = newDef.get("partyTypes");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode pt = newArr.get(i);
                if (pt.has("id")) {
                    String id = pt.get("id").asText();
                    newPartyMap.put(id, pt);
                    newPartyIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("partyTypes");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldPt = oldArr.get(i);
            if (!oldPt.has("id")) continue;
            String id = oldPt.get("id").asText();
            if (!newPartyMap.containsKey(id)) {
                errors.add(new ValidationError("/partyTypes", "party type cannot be removed"));
                continue;
            }
            JsonNode newPt = newPartyMap.get(id);
            int newPtIdx = newPartyIndexMap.get(id);
            validateFields(oldPt, newPt, "/partyTypes/" + newPtIdx + "/fields", errors);
        }
    }

    private void validateRecordTypes(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("recordTypes") || !oldDef.get("recordTypes").isArray()) {
            return;
        }
        Map<String, JsonNode> newRecordMap = new HashMap<>();
        Map<String, Integer> newRecordIndexMap = new HashMap<>();
        if (newDef.has("recordTypes") && newDef.get("recordTypes").isArray()) {
            JsonNode newArr = newDef.get("recordTypes");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode rt = newArr.get(i);
                if (rt.has("id")) {
                    String id = rt.get("id").asText();
                    newRecordMap.put(id, rt);
                    newRecordIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("recordTypes");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldRt = oldArr.get(i);
            if (!oldRt.has("id")) continue;
            String id = oldRt.get("id").asText();
            if (!newRecordMap.containsKey(id)) {
                errors.add(new ValidationError("/recordTypes", "record type cannot be removed"));
                continue;
            }
            JsonNode newRt = newRecordMap.get(id);
            int newRtIdx = newRecordIndexMap.get(id);
            validateFields(oldRt, newRt, "/recordTypes/" + newRtIdx + "/fields", errors);
        }
    }

    private void validateFields(JsonNode oldParent, JsonNode newParent, String basePath, List<ValidationError> errors) {
        if (!oldParent.has("fields") || !oldParent.get("fields").isArray()) {
            return;
        }
        Map<String, JsonNode> newFieldMap = new HashMap<>();
        Map<String, Integer> newFieldIndexMap = new HashMap<>();
        if (newParent.has("fields") && newParent.get("fields").isArray()) {
            JsonNode newArr = newParent.get("fields");
            for (int j = 0; j < newArr.size(); j++) {
                JsonNode f = newArr.get(j);
                if (f.has("id")) {
                    String fId = f.get("id").asText();
                    newFieldMap.put(fId, f);
                    newFieldIndexMap.put(fId, j);
                }
            }
        }

        JsonNode oldArr = oldParent.get("fields");
        for (int j = 0; j < oldArr.size(); j++) {
            JsonNode oldField = oldArr.get(j);
            if (!oldField.has("id")) continue;
            String fId = oldField.get("id").asText();
            if (!newFieldMap.containsKey(fId)) {
                errors.add(new ValidationError(basePath, "field cannot be removed"));
                continue;
            }
            JsonNode newField = newFieldMap.get(fId);
            int newFieldIdx = newFieldIndexMap.get(fId);
            String fieldPath = basePath + "/" + newFieldIdx;

            String oldType = oldField.has("type") ? oldField.get("type").asText() : "";
            String newType = newField.has("type") ? newField.get("type").asText() : "";
            if (!Objects.equals(oldType, newType)) {
                errors.add(new ValidationError(fieldPath + "/type", "field type cannot be changed"));
            }

            if ("party_ref".equals(oldType) || "record_ref".equals(oldType)) {
                String oldTarget = oldField.has("target") ? oldField.get("target").asText() : "";
                String newTarget = newField.has("target") ? newField.get("target").asText() : "";
                if (!Objects.equals(oldTarget, newTarget)) {
                    errors.add(new ValidationError(fieldPath + "/target", "reference target cannot be changed"));
                }
            }

            if ("select".equals(oldType) && oldField.has("options") && oldField.get("options").isArray()) {
                validateSelectOptions(oldField.get("options"), newField, fieldPath + "/options", errors);
            }
        }
    }

    private void validateSelectOptions(JsonNode oldOptions, JsonNode newField, String basePath, List<ValidationError> errors) {
        Set<String> newOptionIds = new HashSet<>();
        if (newField.has("options") && newField.get("options").isArray()) {
            for (JsonNode opt : newField.get("options")) {
                if (opt.has("id")) {
                    newOptionIds.add(opt.get("id").asText());
                }
            }
        }
        for (JsonNode oldOpt : oldOptions) {
            if (oldOpt.has("id")) {
                String optId = oldOpt.get("id").asText();
                if (!newOptionIds.contains(optId)) {
                    errors.add(new ValidationError(basePath, "select option cannot be removed"));
                }
            }
        }
    }

    private void validateChargeSchedules(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("chargeSchedules") || !oldDef.get("chargeSchedules").isArray()) {
            return;
        }
        Map<String, JsonNode> newScheduleMap = new HashMap<>();
        Map<String, Integer> newScheduleIndexMap = new HashMap<>();
        if (newDef.has("chargeSchedules") && newDef.get("chargeSchedules").isArray()) {
            JsonNode newArr = newDef.get("chargeSchedules");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode s = newArr.get(i);
                if (s.has("id")) {
                    String id = s.get("id").asText();
                    newScheduleMap.put(id, s);
                    newScheduleIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("chargeSchedules");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldSched = oldArr.get(i);
            if (!oldSched.has("id")) continue;
            String id = oldSched.get("id").asText();
            if (!newScheduleMap.containsKey(id)) {
                errors.add(new ValidationError("/chargeSchedules", "charge schedule cannot be removed"));
                continue;
            }
            JsonNode newSched = newScheduleMap.get(id);
            int newIdx = newScheduleIndexMap.get(id);
            String path = "/chargeSchedules/" + newIdx;

            String oldKind = oldSched.has("kind") ? oldSched.get("kind").asText() : "";
            String newKind = newSched.has("kind") ? newSched.get("kind").asText() : "";
            if (!Objects.equals(oldKind, newKind)) {
                errors.add(new ValidationError(path + "/kind", "charge schedule kind cannot be changed"));
            }

            String oldPartyType = oldSched.has("partyType") ? oldSched.get("partyType").asText() : "";
            String newPartyType = newSched.has("partyType") ? newSched.get("partyType").asText() : "";
            if (!Objects.equals(oldPartyType, newPartyType)) {
                errors.add(new ValidationError(path + "/partyType", "charge schedule party type cannot be changed"));
            }
        }
    }

    private void validateReminderRules(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("reminderRules") || !oldDef.get("reminderRules").isArray()) {
            return;
        }
        Map<String, JsonNode> newRuleMap = new HashMap<>();
        Map<String, Integer> newRuleIndexMap = new HashMap<>();
        if (newDef.has("reminderRules") && newDef.get("reminderRules").isArray()) {
            JsonNode newArr = newDef.get("reminderRules");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode r = newArr.get(i);
                if (r.has("id")) {
                    String id = r.get("id").asText();
                    newRuleMap.put(id, r);
                    newRuleIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("reminderRules");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldRule = oldArr.get(i);
            if (!oldRule.has("id")) continue;
            String id = oldRule.get("id").asText();
            if (!newRuleMap.containsKey(id)) {
                errors.add(new ValidationError("/reminderRules", "reminder rule cannot be removed"));
                continue;
            }
            JsonNode newRule = newRuleMap.get(id);
            int newIdx = newRuleIndexMap.get(id);
            String path = "/reminderRules/" + newIdx;

            String oldTrigger = oldRule.has("trigger") ? oldRule.get("trigger").asText() : "";
            String newTrigger = newRule.has("trigger") ? newRule.get("trigger").asText() : "";
            if (!Objects.equals(oldTrigger, newTrigger)) {
                errors.add(new ValidationError(path + "/trigger", "reminder rule trigger cannot be changed"));
            }

            String oldPartyType = oldRule.has("partyType") ? oldRule.get("partyType").asText() : "";
            String newPartyType = newRule.has("partyType") ? newRule.get("partyType").asText() : "";
            if (!Objects.equals(oldPartyType, newPartyType)) {
                errors.add(new ValidationError(path + "/partyType", "reminder rule party type cannot be changed"));
            }
        }
    }

    private void validateDocumentChecklists(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("documentChecklists") || !oldDef.get("documentChecklists").isArray()) {
            return;
        }
        Map<String, JsonNode> newChecklistMap = new HashMap<>();
        Map<String, Integer> newChecklistIndexMap = new HashMap<>();
        if (newDef.has("documentChecklists") && newDef.get("documentChecklists").isArray()) {
            JsonNode newArr = newDef.get("documentChecklists");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode c = newArr.get(i);
                if (c.has("id")) {
                    String id = c.get("id").asText();
                    newChecklistMap.put(id, c);
                    newChecklistIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("documentChecklists");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldChecklist = oldArr.get(i);
            if (!oldChecklist.has("id")) continue;
            String id = oldChecklist.get("id").asText();
            if (!newChecklistMap.containsKey(id)) {
                errors.add(new ValidationError("/documentChecklists", "document checklist cannot be removed"));
                continue;
            }
            JsonNode newChecklist = newChecklistMap.get(id);
            int newIdx = newChecklistIndexMap.get(id);

            if (oldChecklist.has("items") && oldChecklist.get("items").isArray()) {
                Set<String> newItemIds = new HashSet<>();
                if (newChecklist.has("items") && newChecklist.get("items").isArray()) {
                    for (JsonNode it : newChecklist.get("items")) {
                        if (it.has("id")) {
                            newItemIds.add(it.get("id").asText());
                        }
                    }
                }
                for (JsonNode oldItem : oldChecklist.get("items")) {
                    if (oldItem.has("id")) {
                        String itemId = oldItem.get("id").asText();
                        if (!newItemIds.contains(itemId)) {
                            errors.add(new ValidationError("/documentChecklists/" + newIdx + "/items", "document item cannot be removed"));
                        }
                    }
                }
            }
        }
    }

    private void validateWidgets(JsonNode oldDef, JsonNode newDef, List<ValidationError> errors) {
        if (!oldDef.has("widgets") || !oldDef.get("widgets").isArray()) {
            return;
        }
        Map<String, JsonNode> newWidgetMap = new HashMap<>();
        Map<String, Integer> newWidgetIndexMap = new HashMap<>();
        if (newDef.has("widgets") && newDef.get("widgets").isArray()) {
            JsonNode newArr = newDef.get("widgets");
            for (int i = 0; i < newArr.size(); i++) {
                JsonNode w = newArr.get(i);
                if (w.has("id")) {
                    String id = w.get("id").asText();
                    newWidgetMap.put(id, w);
                    newWidgetIndexMap.put(id, i);
                }
            }
        }

        JsonNode oldArr = oldDef.get("widgets");
        for (int i = 0; i < oldArr.size(); i++) {
            JsonNode oldWidget = oldArr.get(i);
            if (!oldWidget.has("id")) continue;
            String id = oldWidget.get("id").asText();
            if (!newWidgetMap.containsKey(id)) {
                errors.add(new ValidationError("/widgets", "widget cannot be removed"));
                continue;
            }
            JsonNode newWidget = newWidgetMap.get(id);
            int newIdx = newWidgetIndexMap.get(id);
            String path = "/widgets/" + newIdx;

            String oldType = oldWidget.has("type") ? oldWidget.get("type").asText() : "";
            String newType = newWidget.has("type") ? newWidget.get("type").asText() : "";
            if (!Objects.equals(oldType, newType)) {
                errors.add(new ValidationError(path + "/type", "widget type cannot be changed"));
            }
        }
    }
}
