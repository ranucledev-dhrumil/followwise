package com.followwise.app;

import com.followwise.appdef.AppDefinitionValidator;
import com.followwise.appdef.TemplateRegistry;
import com.followwise.appdef.TransitionValidator;
import com.followwise.appdef.ValidationError;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class AppService {

    private final AppStore appStore;
    private final AccessService accessService;
    private final TemplateRegistry templateRegistry;
    private final AppDefinitionValidator appDefinitionValidator;
    private final TransitionValidator transitionValidator;
    private final AppPayloadParser appPayloadParser;
    private final int maxAppsPerWorkspace;
    private final int maxDefinitionVersionsPerApp;

    public AppService(
            AppStore appStore,
            AccessService accessService,
            TemplateRegistry templateRegistry,
            AppDefinitionValidator appDefinitionValidator,
            TransitionValidator transitionValidator,
            AppPayloadParser appPayloadParser,
            @Value("${app.max-apps-per-workspace:5}") int maxAppsPerWorkspace,
            @Value("${app.max-definition-versions-per-app:200}") int maxDefinitionVersionsPerApp
    ) {
        this.appStore = appStore;
        this.accessService = accessService;
        this.templateRegistry = templateRegistry;
        this.appDefinitionValidator = appDefinitionValidator;
        this.transitionValidator = transitionValidator;
        this.appPayloadParser = appPayloadParser;
        this.maxAppsPerWorkspace = maxAppsPerWorkspace;
        this.maxDefinitionVersionsPerApp = maxDefinitionVersionsPerApp;
    }

    @Transactional
    public AppResponse createApp(UUID workspaceId, UUID userId, AppPayloadParser.ParsedCreateAppRequest req) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_APPS);

        appStore.lockWorkspace(workspaceId);

        long active = appStore.countActiveApps(workspaceId);
        long total = appStore.countTotalApps(workspaceId);
        if (active >= maxAppsPerWorkspace || total >= 5L * maxAppsPerWorkspace) {
            throw new AppConflictException("limit_exceeded", "Workspace has reached the maximum number of apps");
        }

        if (req.name() == null || req.name().isEmpty() || req.name().length() > 100 || !req.name().matches("^[^<>\\u0000-\\u001F\\u007F]+$")) {
            throw new AppValidationException(List.of(new ValidationError("/name", "value does not match required format")));
        }

        if (req.timezone() != null) {
            if (!ZoneId.getAvailableZoneIds().contains(req.timezone())) {
                throw new AppValidationException(List.of(new ValidationError("/timezone", "value does not match required format")));
            }
        }

        JsonNode templateNode = templateRegistry.getTemplate(req.templateKey())
                .orElseThrow(() -> new AppNotFoundException("Template not found"));

        ObjectNode copy = (ObjectNode) templateNode.deepCopy();
        copy.put("name", req.name());
        if (req.timezone() != null) {
            copy.put("timezone", req.timezone());
        }

        String defJson;
        try {
            defJson = appPayloadParser.getJsonMapper().writeValueAsString(copy);
        } catch (Exception e) {
            throw new AppBadRequestException("failed to serialize template definition");
        }

        List<ValidationError> errors = appDefinitionValidator.validate(defJson);
        if (!errors.isEmpty()) {
            throw new AppValidationException(errors);
        }

        UUID appId = UUID.randomUUID();
        Instant now = Instant.now();
        appStore.insertApp(appId, workspaceId, req.name(), req.templateKey(), 1, now);

        UUID versionId = UUID.randomUUID();
        appStore.insertVersion(versionId, appId, 1, defJson, null, userId, now);

        return new AppResponse(appId, workspaceId, req.name(), req.templateKey(), 1, now, now, null, copy);
    }

    @Transactional(readOnly = true)
    public List<AppSummaryResponse> listApps(UUID workspaceId, UUID userId, boolean includeArchived) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_APP);

        return appStore.findAppsByWorkspaceId(workspaceId, includeArchived).stream()
                .map(app -> new AppSummaryResponse(
                        app.id(),
                        app.workspaceId(),
                        app.name(),
                        app.templateKey(),
                        app.currentVersion(),
                        app.createdAt(),
                        app.updatedAt(),
                        app.archivedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AppResponse getApp(UUID workspaceId, UUID appId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_APP);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new AppNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = appPayloadParser.getJsonMapper().readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        return new AppResponse(
                app.id(),
                app.workspaceId(),
                app.name(),
                app.templateKey(),
                app.currentVersion(),
                app.createdAt(),
                app.updatedAt(),
                app.archivedAt(),
                defNode
        );
    }

    @Transactional(readOnly = true)
    public List<AppVersionSummaryResponse> listVersions(UUID workspaceId, UUID appId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_APP);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        return appStore.findVersionSummaries(appId).stream()
                .map(v -> new AppVersionSummaryResponse(
                        v.id(),
                        v.appId(),
                        v.version(),
                        v.note(),
                        v.createdBy(),
                        v.createdAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AppVersionResponse getVersion(UUID workspaceId, UUID appId, int version, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_APP);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, version)
                .orElseThrow(() -> new AppNotFoundException("Version not found"));

        JsonNode defNode;
        try {
            defNode = appPayloadParser.getJsonMapper().readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        return new AppVersionResponse(
                ver.id(),
                ver.appId(),
                ver.version(),
                ver.note(),
                ver.createdBy(),
                ver.createdAt(),
                defNode
        );
    }

    @Transactional
    public AppResponse putDefinition(UUID workspaceId, UUID appId, UUID userId, AppPayloadParser.ParsedPutDefinitionRequest req) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_APPS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new AppConflictException("app_archived", "Cannot modify an archived app");
        }

        if (req.expectedVersion() != app.currentVersion()) {
            throw new AppConflictException("stale_version", "App version has changed", app.currentVersion());
        }

        AppDefinitionVersionRecord currentVer = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new AppNotFoundException("Current version not found"));

        JsonNode currentDefNode;
        try {
            currentDefNode = appPayloadParser.getJsonMapper().readTree(currentVer.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored current definition", e);
        }

        JsonNode newDefNode = req.definition();

        if (currentDefNode.equals(newDefNode)) {
            return new AppResponse(
                    app.id(),
                    app.workspaceId(),
                    app.name(),
                    app.templateKey(),
                    app.currentVersion(),
                    app.createdAt(),
                    app.updatedAt(),
                    app.archivedAt(),
                    currentDefNode
            );
        }

        String newDefJson;
        try {
            newDefJson = appPayloadParser.getJsonMapper().writeValueAsString(newDefNode);
        } catch (Exception e) {
            throw new AppBadRequestException("failed to serialize definition");
        }

        List<ValidationError> errors = appDefinitionValidator.validate(newDefJson);
        if (!errors.isEmpty()) {
            throw new AppValidationException(errors);
        }

        List<ValidationError> transitionErrors = transitionValidator.validateTransition(currentDefNode, newDefNode);
        if (!transitionErrors.isEmpty()) {
            throw new AppValidationException(transitionErrors);
        }

        long verCount = appStore.countVersions(appId);
        if (verCount >= maxDefinitionVersionsPerApp) {
            throw new AppConflictException("version_limit", "Maximum number of definition versions reached");
        }

        String nameFromDef = newDefNode.hasNonNull("name") ? newDefNode.get("name").asText() : app.name();
        if (nameFromDef.length() > 100) {
            throw new AppValidationException(List.of(new ValidationError("/name", "too long")));
        }

        int newVersion = req.expectedVersion() + 1;
        Instant now = Instant.now();

        int rowsUpdated = appStore.updateAppDefinitionAndVersion(appId, workspaceId, req.expectedVersion(), newVersion, nameFromDef, now);
        if (rowsUpdated == 0) {
            AppRecord latest = appStore.findAppByIdAndWorkspaceId(appId, workspaceId).orElse(app);
            throw new AppConflictException("stale_version", "App version has changed", latest.currentVersion());
        }

        UUID versionId = UUID.randomUUID();
        appStore.insertVersion(versionId, appId, newVersion, newDefJson, req.note(), userId, now);

        return new AppResponse(
                app.id(),
                app.workspaceId(),
                nameFromDef,
                app.templateKey(),
                newVersion,
                app.createdAt(),
                now,
                null,
                newDefNode
        );
    }

    @Transactional
    public void archiveApp(UUID workspaceId, UUID appId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_APPS);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new AppNotFoundException("App not found"));

        appStore.archiveApp(appId, workspaceId, Instant.now());
    }
}
