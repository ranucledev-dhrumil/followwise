package com.followwise.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DashboardResponse(
        int definitionVersion,
        LocalDate today,
        String month,
        Instant generatedAt,
        List<WidgetResult> widgets
) {
    public record WidgetResult(
            String id,
            String type,
            String label,
            String status,
            String reason,
            Object data
    ) {}

    public record CountData(long count) {}

    public record SumData(long cents, String currency) {}

    public record ListData<T>(List<T> items, long totalCount) {}

    public record OverduePartyItem(
            UUID partyId,
            String displayName,
            long overdueCents,
            LocalDate oldestDueDate,
            long overdueChargeCount
    ) {}

    public record ExpiringLeaseItem(
            UUID partyId,
            String displayName,
            LocalDate date,
            int daysLeft
    ) {}

    public record MissingDocsPartyItem(
            UUID partyId,
            String displayName,
            long missingCount,
            LocalDate oldestDueDate
    ) {}

    public record ExpiringDocItem(
            UUID requestId,
            UUID partyId,
            String displayName,
            String label,
            LocalDate expiresOn,
            int daysLeft
    ) {}

    public record CompletionPctPartyItem(
            UUID partyId,
            String displayName,
            int pct,
            long requiredCount,
            long approvedCount
    ) {}

    public record CompletionPctData(
            List<CompletionPctPartyItem> items,
            long totalCount,
            int overallPct
    ) {}
}
