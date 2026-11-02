package com.followwise.workspace;

import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AccessService accessService;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            MembershipRepository membershipRepository,
                            UserRepository userRepository,
                            AccessService accessService) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
    }

    public List<WorkspaceResponse> listUserWorkspaces(UUID userId) {
        List<Membership> memberships = membershipRepository.findByUserId(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<UUID> workspaceIds = memberships.stream().map(Membership::getWorkspaceId).toList();
        Map<UUID, Workspace> workspaceMap = workspaceRepository.findAllById(workspaceIds)
                .stream().collect(Collectors.toMap(Workspace::getId, w -> w));

        return memberships.stream()
                .filter(m -> workspaceMap.containsKey(m.getWorkspaceId()))
                .map(m -> {
                    Workspace w = workspaceMap.get(m.getWorkspaceId());
                    return new WorkspaceResponse(w.getId(), w.getName(), m.getRole(), w.getCreatedAt());
                })
                .toList();
    }

    public WorkspaceResponse getWorkspace(UUID workspaceId, UUID userId) {
        Membership membership = accessService.requirePermission(workspaceId, userId, Permission.VIEW_WORKSPACE);
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException("Workspace not found"));
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), membership.getRole(), workspace.getCreatedAt());
    }

    public List<MemberResponse> listMembers(UUID workspaceId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_MEMBERS);
        List<Membership> memberships = membershipRepository.findByWorkspaceId(workspaceId);
        List<UUID> userIds = memberships.stream().map(Membership::getUserId).toList();
        Map<UUID, String> userEmailMap = userRepository.findAllById(userIds)
                .stream().collect(Collectors.toMap(User::getId, User::getEmail));

        return memberships.stream()
                .map(m -> new MemberResponse(
                        m.getId(),
                        m.getUserId(),
                        userEmailMap.getOrDefault(m.getUserId(), ""),
                        m.getRole(),
                        m.getCreatedAt()
                ))
                .toList();
    }
}
