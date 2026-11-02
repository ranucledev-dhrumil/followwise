package com.followwise.identity;

import com.followwise.workspace.Role;

import java.util.UUID;

public record WorkspaceMembershipDto(
        UUID workspaceId,
        String workspaceName,
        Role role
) {}
