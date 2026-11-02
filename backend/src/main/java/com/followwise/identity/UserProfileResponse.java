package com.followwise.identity;

import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        List<WorkspaceMembershipDto> workspaces
) {}
