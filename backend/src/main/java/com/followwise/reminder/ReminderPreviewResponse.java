package com.followwise.reminder;

import java.util.List;

public record ReminderPreviewResponse(
        boolean remindersEnabled,
        boolean blockedByBacklog,
        boolean capReached,
        boolean scanLimitReached,
        List<ReminderEngineService.ReminderPreviewItem> items
) {}
