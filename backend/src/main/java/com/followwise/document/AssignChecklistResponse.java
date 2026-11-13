package com.followwise.document;

import java.util.List;

public record AssignChecklistResponse(
        List<DocumentRequestResponse> created,
        int skippedExisting
) {
}
