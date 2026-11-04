package com.followwise.party;

import java.util.UUID;

public interface RefChecker {
    enum TargetStatus {
        VALID,
        ARCHIVED,
        NOT_FOUND
    }

    TargetStatus checkPartyRef(UUID targetId, String targetType);
    TargetStatus checkRecordRef(UUID targetId, String targetType);
}
