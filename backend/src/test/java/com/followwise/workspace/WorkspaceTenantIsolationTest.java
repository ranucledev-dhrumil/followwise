package com.followwise.workspace;

import com.followwise.AbstractIntegrationTest;
import com.followwise.identity.User;
import com.followwise.identity.UserRepository;
import com.followwise.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WorkspaceTenantIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TokenService tokenService;

    private UUID workspace1Id;
    private UUID workspace2Id;

    private String ownerToken;
    private String staffToken;
    private String portalToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() {
        membershipRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        Instant now = Instant.now();

        // Workspace 1
        workspace1Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace1Id, "Workspace 1", now));

        // Workspace 2
        workspace2Id = UUID.randomUUID();
        workspaceRepository.save(new Workspace(workspace2Id, "Workspace 2", now));

        // Users
        UUID ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, "owner@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), ownerId, workspace1Id, Role.OWNER, now));
        ownerToken = tokenService.issueAccessToken(ownerId, now);

        UUID staffId = UUID.randomUUID();
        userRepository.save(new User(staffId, "staff@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), staffId, workspace1Id, Role.STAFF, now));
        staffToken = tokenService.issueAccessToken(staffId, now);

        UUID portalId = UUID.randomUUID();
        userRepository.save(new User(portalId, "portal@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), portalId, workspace1Id, Role.PORTAL_USER, now));
        portalToken = tokenService.issueAccessToken(portalId, now);

        UUID nonMemberId = UUID.randomUUID();
        userRepository.save(new User(nonMemberId, "nonmember@example.com", "hash", now));
        membershipRepository.save(new Membership(UUID.randomUUID(), nonMemberId, workspace2Id, Role.OWNER, now));
        nonMemberToken = tokenService.issueAccessToken(nonMemberId, now);
    }

    @Test
    void getWorkspacePermissionsAndTenantIsolation() throws Exception {
        // Non-member gets 404 (no existence leak)
        mockMvc.perform(get("/api/workspaces/" + workspace1Id)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));

        // Portal user has neither permission -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));

        // Staff has VIEW_WORKSPACE -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspace1Id.toString()))
                .andExpect(jsonPath("$.role").value("STAFF"));

        // Owner has VIEW_WORKSPACE -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspace1Id.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void getMembersPermissionsAndTenantIsolation() throws Exception {
        // Non-member gets 404
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/members")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));

        // Portal user has neither permission -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/members")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));

        // Staff does NOT have VIEW_MEMBERS -> 403
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/members")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));

        // Owner has VIEW_MEMBERS -> 200
        mockMvc.perform(get("/api/workspaces/" + workspace1Id + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void listWorkspacesOnlyReturnsUserMemberships() throws Exception {
        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(workspace1Id.toString()));

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(workspace2Id.toString()));
    }
}
