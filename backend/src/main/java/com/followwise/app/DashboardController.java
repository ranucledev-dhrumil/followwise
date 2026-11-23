package com.followwise.app;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/apps/{appId}/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("appId") UUID appId,
            @RequestParam(value = "month", required = false) String month,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        DashboardResponse response = dashboardService.getDashboard(workspaceId, appId, userId, month);
        return ResponseEntity.ok(response);
    }
}
