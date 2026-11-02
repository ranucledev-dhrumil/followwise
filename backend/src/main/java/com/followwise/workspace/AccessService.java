package com.followwise.workspace;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccessService {

    private final MembershipRepository membershipRepository;

    public AccessService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public Membership requireMember(UUID workspaceId, UUID userId) {
        return membershipRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException("Workspace not found"));
    }

    public Membership requirePermission(UUID workspaceId, UUID userId, Permission permission) {
        Membership membership = requireMember(workspaceId, userId);
        if (!permission.isGrantedTo(membership.getRole())) {
            throw new ForbiddenException("Forbidden: lacking permission " + permission.name());
        }
        return membership;
    }
}
