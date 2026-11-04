package com.followwise.party;

import com.followwise.app.AppDefinitionVersionRecord;
import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.appdef.ValidationError;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RecordService {

    private final RecordStore recordStore;
    private final PartyStore partyStore;
    private final AppStore appStore;
    private final AccessService accessService;
    private final FieldValidator fieldValidator;
    private final JsonMapper jsonMapper;
    private final int maxRecordsPerApp;

    public RecordService(
            RecordStore recordStore,
            PartyStore partyStore,
            AppStore appStore,
            AccessService accessService,
            FieldValidator fieldValidator,
            @Value("${app.max-records-per-app:500}") int maxRecordsPerApp
    ) {
        this.recordStore = recordStore;
        this.partyStore = partyStore;
        this.appStore = appStore;
        this.accessService = accessService;
        this.fieldValidator = fieldValidator;
        this.jsonMapper = JsonMapper.builder().build();
        this.maxRecordsPerApp = maxRecordsPerApp;
    }

    private RefChecker createRefChecker(UUID workspaceId, UUID appId) {
        return new RefChecker() {
            @Override
            public TargetStatus checkPartyRef(UUID targetId, String targetType) {
                return partyStore.checkRef(workspaceId, appId, targetId, targetType);
            }

            @Override
            public TargetStatus checkRecordRef(UUID targetId, String targetType) {
                return recordStore.checkRef(workspaceId, appId, targetId, targetType);
            }
        };
    }

    @Transactional
    public RecordResponse createRecord(UUID workspaceId, UUID appId, UUID userId, String recordType, JsonNode data) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new PartyConflictException("app_archived", "Cannot modify an archived app");
        }

        if (recordStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new PartyConflictException("app_archived", "App is archived or not found");
        }

        long active = recordStore.countActive(workspaceId, appId);
        long total = recordStore.countTotal(workspaceId, appId);
        if (active >= maxRecordsPerApp || total >= 2L * maxRecordsPerApp) {
            throw new PartyConflictException("limit_exceeded", "Maximum number of records reached");
        }

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new PartyNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        JsonNode typeNode = findTypeNode(defNode, "recordTypes", recordType);
        if (typeNode == null) {
            throw new PartyValidationException(List.of(new ValidationError("/type", "unknown record type")));
        }
        if (typeNode.path("archived").asBoolean(false)) {
            throw new PartyValidationException(List.of(new ValidationError("/type", "archived type cannot be used")));
        }

        RefChecker refChecker = createRefChecker(workspaceId, appId);
        FieldValidationResult res = fieldValidator.validate(typeNode, data, null, refChecker);
        if (!res.isValid()) {
            throw new PartyValidationException(res.errors());
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String dataStr;
        try {
            dataStr = jsonMapper.writeValueAsString(res.normalizedData());
        } catch (Exception e) {
            throw new PartyBadRequestException("failed to serialize data");
        }

        recordStore.insert(id, workspaceId, appId, recordType, res.computedDisplayName(), dataStr, ver.version(), userId, now);

        return new RecordResponse(
                id,
                recordType,
                res.computedDisplayName(),
                res.normalizedData(),
                1,
                ver.version(),
                false,
                now,
                now
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<RecordResponse> listRecords(
            UUID workspaceId, UUID appId, UUID userId, String recordType,
            String q, boolean includeArchived, int page, int size
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        if (recordType == null || recordType.trim().isEmpty()) {
            throw new PartyBadRequestException("type is required");
        }

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new PartyNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        JsonNode typeNode = findTypeNode(defNode, "recordTypes", recordType);
        if (typeNode == null) {
            throw new PartyBadRequestException("unknown type");
        }

        if (q != null && q.length() > 100) {
            throw new PartyBadRequestException("q exceeds 100 characters");
        }

        if (page < 0) {
            throw new PartyBadRequestException("page must be >= 0");
        }
        if (size <= 0) {
            size = 20;
        } else if (size > 50) {
            size = 50;
        }

        List<RecordRow> records = recordStore.findPage(workspaceId, appId, recordType, q, includeArchived, page, size);
        long total = recordStore.countFiltered(workspaceId, appId, recordType, q, includeArchived);

        List<RecordResponse> items = records.stream().map(this::toResponse).toList();
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public RecordResponse getRecord(UUID workspaceId, UUID appId, UUID id, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DATA);

        appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        RecordRow record = recordStore.findById(workspaceId, appId, id)
                .orElseThrow(() -> new PartyNotFoundException("Record not found"));

        return toResponse(record);
    }

    @Transactional
    public RecordResponse updateRecord(UUID workspaceId, UUID appId, UUID id, UUID userId,
                                        int expectedVersion, JsonNode data) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new PartyConflictException("app_archived", "Cannot modify an archived app");
        }

        RecordRow existing = recordStore.findById(workspaceId, appId, id)
                .orElseThrow(() -> new PartyNotFoundException("Record not found"));

        if (existing.isArchived()) {
            throw new PartyConflictException("record_archived", "Cannot modify an archived record");
        }

        if (existing.version() != expectedVersion) {
            throw new PartyConflictException("stale_version", "Record version has changed", existing.version());
        }

        AppDefinitionVersionRecord ver = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new PartyNotFoundException("Current version not found"));

        JsonNode defNode;
        try {
            defNode = jsonMapper.readTree(ver.definition());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored definition", e);
        }

        JsonNode typeNode = findTypeNode(defNode, "recordTypes", existing.recordType());
        if (typeNode == null) {
            throw new PartyValidationException(List.of(new ValidationError("/type", "unknown record type")));
        }
        if (typeNode.path("archived").asBoolean(false)) {
            throw new PartyValidationException(List.of(new ValidationError("/type", "archived type cannot be used")));
        }

        JsonNode previousData;
        try {
            previousData = jsonMapper.readTree(existing.dataJson());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored record data", e);
        }

        RefChecker refChecker = createRefChecker(workspaceId, appId);
        FieldValidationResult res = fieldValidator.validate(typeNode, data, previousData, refChecker);
        if (!res.isValid()) {
            throw new PartyValidationException(res.errors());
        }

        String dataStr;
        try {
            dataStr = jsonMapper.writeValueAsString(res.normalizedData());
        } catch (Exception e) {
            throw new PartyBadRequestException("failed to serialize data");
        }

        Instant now = Instant.now();
        int rows = recordStore.update(workspaceId, appId, id, expectedVersion, res.computedDisplayName(), dataStr, ver.version(), userId, now);
        if (rows == 0) {
            RecordRow latest = recordStore.findById(workspaceId, appId, id).orElse(existing);
            if (latest.isArchived()) {
                throw new PartyConflictException("record_archived", "Cannot modify an archived record");
            }
            throw new PartyConflictException("stale_version", "Record version has changed", latest.version());
        }

        return new RecordResponse(
                id,
                existing.recordType(),
                res.computedDisplayName(),
                res.normalizedData(),
                existing.version() + 1,
                ver.version(),
                false,
                existing.createdAt(),
                now
        );
    }

    @Transactional
    public void archiveRecord(UUID workspaceId, UUID appId, UUID id, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new PartyConflictException("app_archived", "Cannot modify an archived app");
        }

        RecordRow existing = recordStore.findById(workspaceId, appId, id)
                .orElseThrow(() -> new PartyNotFoundException("Record not found"));

        if (existing.isArchived()) {
            return;
        }

        recordStore.archive(workspaceId, appId, id, userId, Instant.now());
    }

    @Transactional
    public void restoreRecord(UUID workspaceId, UUID appId, UUID id, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new PartyNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new PartyConflictException("app_archived", "Cannot modify an archived app");
        }

        RecordRow existing = recordStore.findById(workspaceId, appId, id)
                .orElseThrow(() -> new PartyNotFoundException("Record not found"));

        if (!existing.isArchived()) {
            return;
        }

        recordStore.restore(workspaceId, appId, id, userId, Instant.now());
    }

    private JsonNode findTypeNode(JsonNode defNode, String arrayName, String typeId) {
        JsonNode types = defNode.path(arrayName);
        if (types.isArray()) {
            for (JsonNode t : types) {
                if (t.hasNonNull("id") && t.get("id").asText().equals(typeId)) {
                    return t;
                }
            }
        }
        return null;
    }

    private RecordResponse toResponse(RecordRow record) {
        JsonNode dataNode;
        try {
            dataNode = jsonMapper.readTree(record.dataJson());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored record data", e);
        }
        return new RecordResponse(
                record.id(),
                record.recordType(),
                record.displayName(),
                dataNode,
                record.version(),
                record.definitionVersion(),
                record.isArchived(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
