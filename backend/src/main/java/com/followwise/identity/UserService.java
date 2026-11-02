package com.followwise.identity;

import com.followwise.workspace.Membership;
import com.followwise.workspace.MembershipRepository;
import com.followwise.workspace.Workspace;
import com.followwise.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;

    public UserService(UserRepository userRepository,
                       MembershipRepository membershipRepository,
                       WorkspaceRepository workspaceRepository) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
    }

    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Membership> memberships = membershipRepository.findByUserId(userId);
        List<UUID> workspaceIds = memberships.stream().map(Membership::getWorkspaceId).toList();
        Map<UUID, Workspace> workspaceMap = workspaceRepository.findAllById(workspaceIds)
                .stream().collect(Collectors.toMap(Workspace::getId, w -> w));

        List<WorkspaceMembershipDto> workspaces = memberships.stream()
                .filter(m -> workspaceMap.containsKey(m.getWorkspaceId()))
                .map(m -> new WorkspaceMembershipDto(
                        m.getWorkspaceId(),
                        workspaceMap.get(m.getWorkspaceId()).getName(),
                        m.getRole()
                ))
                .toList();

        boolean emailVerified = user.getEmailVerifiedAt() != null;
        return new UserProfileResponse(user.getId(), user.getEmail(), emailVerified, workspaces);
    }
}
