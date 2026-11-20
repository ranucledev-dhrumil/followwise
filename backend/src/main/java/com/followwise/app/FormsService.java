package com.followwise.app;

import com.followwise.appdef.FieldConstraints;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Service
public class FormsService {

    private final AppStore appStore;
    private final AccessService accessService;
    private final JsonMapper jsonMapper;

    public FormsService(AppStore appStore, AccessService accessService) {
        this.appStore = appStore;
        this.accessService = accessService;
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Transactional(readOnly = true)
    public FormsResponse getForms(UUID workspaceId, UUID appId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new AppNotFoundException("Current version not found"));

        JsonNode def;
        try {
            def = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        String appName = app.name();
        String timezone = def.path("timezone").asText("UTC");
        String currency = def.path("currency").asText("USD");

        List<FormsResponse.PartyTypeForm> partyTypes = buildPartyTypes(def.path("partyTypes"));
        List<FormsResponse.RecordTypeForm> recordTypes = buildRecordTypes(def.path("recordTypes"));
        List<FormsResponse.ChecklistForm> checklists = buildChecklists(def.path("documentChecklists"));
        List<FormsResponse.ChargeScheduleForm> chargeSchedules = buildChargeSchedules(def.path("chargeSchedules"));

        return new FormsResponse(
                ver.version(),
                appName,
                timezone,
                currency,
                partyTypes,
                recordTypes,
                checklists,
                chargeSchedules
        );
    }

    private List<FormsResponse.PartyTypeForm> buildPartyTypes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FormsResponse.PartyTypeForm> result = new ArrayList<>();
        for (JsonNode t : node) {
            if (t.path("archived").asBoolean(false)) {
                continue;
            }
            String id = t.path("id").asText();
            String label = t.path("label").asText();
            String displayNameField = findDisplayNameField(t.path("fields"));
            List<String> listColumns = buildListColumns(t.path("fields"), displayNameField);
            List<FormsResponse.FieldForm> fields = buildFields(t.path("fields"));
            result.add(new FormsResponse.PartyTypeForm(id, label, displayNameField, listColumns, fields));
        }
        return result;
    }

    private List<FormsResponse.RecordTypeForm> buildRecordTypes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FormsResponse.RecordTypeForm> result = new ArrayList<>();
        for (JsonNode t : node) {
            if (t.path("archived").asBoolean(false)) {
                continue;
            }
            String id = t.path("id").asText();
            String label = t.path("label").asText();
            String displayNameField = findDisplayNameField(t.path("fields"));
            List<String> listColumns = buildListColumns(t.path("fields"), displayNameField);
            List<FormsResponse.FieldForm> fields = buildFields(t.path("fields"));
            result.add(new FormsResponse.RecordTypeForm(id, label, displayNameField, listColumns, fields));
        }
        return result;
    }

    private String findDisplayNameField(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return null;
        }
        for (JsonNode f : fieldsNode) {
            boolean archived = f.path("archived").asBoolean(false);
            String type = f.path("type").asText();
            if (!archived && "text".equals(type)) {
                return f.path("id").asText();
            }
        }
        return null;
    }

    private List<String> buildListColumns(JsonNode fieldsNode, String displayNameField) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return List.of();
        }
        List<String> cols = new ArrayList<>();
        if (displayNameField != null) {
            cols.add(displayNameField);
        }
        for (JsonNode f : fieldsNode) {
            if (cols.size() >= 4) {
                break;
            }
            boolean archived = f.path("archived").asBoolean(false);
            if (archived) {
                continue;
            }
            String fid = f.path("id").asText();
            if (!fid.equals(displayNameField)) {
                cols.add(fid);
            }
        }
        return cols;
    }

    private List<FormsResponse.FieldForm> buildFields(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return List.of();
        }
        List<FormsResponse.FieldForm> result = new ArrayList<>();
        for (JsonNode f : fieldsNode) {
            String id = f.path("id").asText();
            String label = f.path("label").asText();
            String type = f.path("type").asText();
            boolean required = f.path("required").asBoolean(false);
            boolean archived = f.path("archived").asBoolean(false);
            boolean hidden = archived;
            boolean readOnly = archived;

            List<FormsResponse.OptionForm> options = null;
            if ("select".equals(type) && f.has("options") && f.get("options").isArray()) {
                options = new ArrayList<>();
                for (JsonNode opt : f.get("options")) {
                    options.add(new FormsResponse.OptionForm(
                            opt.path("id").asText(),
                            opt.path("label").asText(),
                            opt.path("archived").asBoolean(false)
                    ));
                }
            }

            String target = null;
            if (("party_ref".equals(type) || "record_ref".equals(type)) && f.hasNonNull("target")) {
                target = f.get("target").asText();
            }

            Map<String, Object> constraints = buildConstraints(type);

            result.add(new FormsResponse.FieldForm(id, label, type, required, hidden, readOnly, options, target, constraints));
        }
        return result;
    }

    private Map<String, Object> buildConstraints(String type) {
        return switch (type) {
            case "text" -> Map.of(
                    "minLength", FieldConstraints.TEXT_MIN_LENGTH,
                    "maxLength", FieldConstraints.TEXT_MAX_LENGTH
            );
            case "email" -> Map.of(
                    "maxLength", FieldConstraints.EMAIL_MAX_LENGTH
            );
            case "phone" -> Map.of(
                    "minLength", FieldConstraints.PHONE_MIN_LENGTH,
                    "maxLength", FieldConstraints.PHONE_MAX_LENGTH
            );
            case "integer" -> Map.of(
                    "min", FieldConstraints.INTEGER_MIN,
                    "max", FieldConstraints.INTEGER_MAX
            );
            case "money" -> Map.of(
                    "min", FieldConstraints.MONEY_MIN_CENTS,
                    "max", FieldConstraints.MONEY_MAX_CENTS
            );
            case "date" -> Map.of(
                    "format", FieldConstraints.DATE_FORMAT
            );
            default -> null;
        };
    }

    private List<FormsResponse.ChecklistForm> buildChecklists(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FormsResponse.ChecklistForm> result = new ArrayList<>();
        for (JsonNode c : node) {
            if (c.path("archived").asBoolean(false)) {
                continue;
            }
            String id = c.path("id").asText();
            String label = c.path("label").asText();
            String partyType = c.path("partyType").asText();
            List<FormsResponse.ChecklistItemForm> items = new ArrayList<>();
            if (c.has("items") && c.get("items").isArray()) {
                for (JsonNode item : c.get("items")) {
                    if (item.path("archived").asBoolean(false)) {
                        continue;
                    }
                    String itemId = item.path("id").asText();
                    String itemLabel = item.path("label").asText();
                    boolean req = item.path("required").asBoolean(false);
                    Integer dueOffset = item.hasNonNull("dueOffsetDays") ? item.get("dueOffsetDays").asInt() : null;
                    Integer expiryDays = item.hasNonNull("expiryDays") ? item.get("expiryDays").asInt() : null;
                    items.add(new FormsResponse.ChecklistItemForm(itemId, itemLabel, req, dueOffset, expiryDays));
                }
            }
            result.add(new FormsResponse.ChecklistForm(id, label, partyType, items));
        }
        return result;
    }

    private List<FormsResponse.ChargeScheduleForm> buildChargeSchedules(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FormsResponse.ChargeScheduleForm> result = new ArrayList<>();
        for (JsonNode s : node) {
            if (s.path("archived").asBoolean(false)) {
                continue;
            }
            String id = s.path("id").asText();
            String label = s.path("label").asText();
            String kind = s.path("kind").asText();
            String partyType = s.path("partyType").asText();
            boolean enabled = s.path("enabled").asBoolean(false);
            result.add(new FormsResponse.ChargeScheduleForm(id, label, kind, partyType, enabled));
        }
        return result;
    }
}
