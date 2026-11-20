package com.followwise.app;

import java.util.List;
import java.util.Map;

public record FormsResponse(
        int definitionVersion,
        String appName,
        String timezone,
        String currency,
        List<PartyTypeForm> partyTypes,
        List<RecordTypeForm> recordTypes,
        List<ChecklistForm> checklists,
        List<ChargeScheduleForm> chargeSchedules
) {
    public record PartyTypeForm(
            String id,
            String label,
            String displayNameField,
            List<String> listColumns,
            List<FieldForm> fields
    ) {}

    public record RecordTypeForm(
            String id,
            String label,
            String displayNameField,
            List<String> listColumns,
            List<FieldForm> fields
    ) {}

    public record FieldForm(
            String id,
            String label,
            String type,
            boolean required,
            boolean hidden,
            boolean readOnly,
            List<OptionForm> options,
            String target,
            Map<String, Object> constraints
    ) {}

    public record OptionForm(
            String id,
            String label,
            boolean archived
    ) {}

    public record ChecklistForm(
            String id,
            String label,
            String partyType,
            List<ChecklistItemForm> items
    ) {}

    public record ChecklistItemForm(
            String id,
            String label,
            boolean required,
            Integer dueOffsetDays,
            Integer expiryDays
    ) {}

    public record ChargeScheduleForm(
            String id,
            String label,
            String kind,
            String partyType,
            boolean enabled
    ) {}
}
