package com.followwise.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.followwise.app.AppDefinitionVersionRecord;
import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.appdef.ValidationError;
import com.followwise.party.PageResponse;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyStore;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class DocumentService {

    private final DocumentRequestStore documentRequestStore;
    private final DocumentStore documentStore;
    private final DocumentStorage documentStorage;
    private final PartyStore partyStore;
    private final AppStore appStore;
    private final AccessService accessService;
    private final StorageConfig storageConfig;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final Semaphore uploadSemaphore;

    public DocumentService(
            DocumentRequestStore documentRequestStore,
            DocumentStore documentStore,
            DocumentStorage documentStorage,
            PartyStore partyStore,
            AppStore appStore,
            AccessService accessService,
            StorageConfig storageConfig,
            Clock clock
    ) {
        this.documentRequestStore = documentRequestStore;
        this.documentStore = documentStore;
        this.documentStorage = documentStorage;
        this.partyStore = partyStore;
        this.appStore = appStore;
        this.accessService = accessService;
        this.storageConfig = storageConfig;
        this.clock = clock;
        this.jsonMapper = JsonMapper.builder().build();
        this.uploadSemaphore = new Semaphore(storageConfig.getMaxConcurrentUploads());
    }

    public Semaphore getUploadSemaphore() {
        return uploadSemaphore;
    }

    @Transactional
    public AssignChecklistResponse assignChecklist(
            UUID workspaceId, UUID appId, UUID partyId, UUID userId, String checklistId
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot assign checklist on an archived app");
        }

        PartyRecord party = partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));

        if (party.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot assign checklist on an archived party");
        }

        AppDefinitionVersionRecord defVersion = appStore.findVersion(appId, app.currentVersion())
                .orElseThrow(() -> new DocumentNotFoundException("App definition version not found"));

        JsonNode root;
        try {
            root = jsonMapper.readTree(defVersion.definition());
        } catch (Exception e) {
            throw new DocumentValidationException(List.of(new ValidationError("/definition", "Malformed app definition")));
        }

        JsonNode checklistsNode = root.path("documentChecklists");
        if (!checklistsNode.isArray() || checklistsNode.isEmpty()) {
            throw new DocumentValidationException(List.of(new ValidationError("/checklistId", "No document checklists defined in app")));
        }

        JsonNode targetChecklist = null;
        for (JsonNode cl : checklistsNode) {
            if (checklistId.equals(cl.path("id").asText())) {
                targetChecklist = cl;
                break;
            }
        }

        if (targetChecklist == null) {
            throw new DocumentValidationException(List.of(new ValidationError("/checklistId", "Unknown checklistId: " + checklistId)));
        }

        if (targetChecklist.path("archived").asBoolean(false)) {
            throw new DocumentValidationException(List.of(new ValidationError("/checklistId", "Checklist is archived")));
        }

        // Validate partyType matches
        String appliesToPartyType = targetChecklist.path("partyType").asText(null);
        if (appliesToPartyType != null && !appliesToPartyType.isEmpty() && !appliesToPartyType.equals(party.partyType())) {
            throw new DocumentValidationException(List.of(new ValidationError("/checklistId",
                    "Checklist party type '" + appliesToPartyType + "' does not match party type '" + party.partyType() + "'")));
        }

        JsonNode itemsNode = targetChecklist.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new DocumentValidationException(List.of(new ValidationError("/documentChecklists/items", "Checklist has no items")));
        }

        // Lock order: app -> party
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new DocumentConflictException("app_archived", "App is archived or not found");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, partyId).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        String tz = root.path("timezone").asText("UTC");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tz);
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }
        LocalDate todayInApp = LocalDate.ofInstant(clock.instant(), zoneId);

        List<DocumentRequestResponse> createdList = new ArrayList<>();
        int skippedExisting = 0;
        Instant now = clock.instant();

        for (JsonNode item : itemsNode) {
            if (item.path("archived").asBoolean(false)) {
                continue;
            }
            String itemId = item.path("id").asText();
            if (documentRequestStore.existsActiveItem(workspaceId, appId, partyId, checklistId, itemId)) {
                skippedExisting++;
                continue;
            }

            // Check max requests per party before adding
            long currentCount = documentRequestStore.countActiveByParty(workspaceId, appId, partyId);
            if (currentCount >= storageConfig.getMaxRequestsPerParty()) {
                throw new DocumentConflictException("limit_exceeded",
                        "Assigning this checklist would exceed max requests per party (" + storageConfig.getMaxRequestsPerParty() + ")");
            }

            String label = item.path("label").asText();
            boolean required = item.path("required").asBoolean(true);
            Integer expiryDays = item.hasNonNull("expiryDays") ? item.get("expiryDays").asInt() : null;
            LocalDate dueDate = null;
            if (item.hasNonNull("dueOffsetDays")) {
                dueDate = todayInApp.plusDays(item.get("dueOffsetDays").asInt());
            }

            UUID reqId = UUID.randomUUID();
            documentRequestStore.insert(
                    reqId, workspaceId, appId, partyId, checklistId, itemId, label,
                    required, expiryDays, dueDate, "PENDING", userId, now
            );

            DocumentRequestRecord createdRecord = documentRequestStore.findById(workspaceId, appId, reqId).orElseThrow();
            createdList.add(DocumentRequestResponse.from(createdRecord));
        }

        return new AssignChecklistResponse(createdList, skippedExisting);
    }

    @Transactional(readOnly = true)
    public List<DocumentRequestResponse> listPartyRequests(
            UUID workspaceId, UUID appId, UUID partyId, UUID userId
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));

        return documentRequestStore.findByParty(workspaceId, appId, partyId)
                .stream().map(DocumentRequestResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentRequestResponse> listRequests(
            UUID workspaceId, UUID appId, UUID userId, int page, int size
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        int sanitizedSize = Math.max(1, Math.min(size, 50));
        int sanitizedPage = Math.max(0, page);

        List<DocumentRequestRecord> records = documentRequestStore.findPage(workspaceId, appId, null, sanitizedPage, sanitizedSize);
        long total = documentRequestStore.countFiltered(workspaceId, appId, null);
        List<DocumentRequestResponse> items = records.stream().map(DocumentRequestResponse::from).toList();

        return new PageResponse<>(items, sanitizedPage, sanitizedSize, total);
    }

    @Transactional(readOnly = true)
    public DocumentRequestResponse getRequest(UUID workspaceId, UUID appId, UUID requestId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        DocumentRequestRecord record = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();

        return DocumentRequestResponse.from(record, versions);
    }

    @Transactional
    public UploadDocumentResult uploadDocument(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            byte[] bytes, String rawFilename
    ) {
        if (!uploadSemaphore.tryAcquire()) {
            throw new DocumentUploadBusyException();
        }
        try {
            return doUploadDocument(workspaceId, appId, requestId, userId, bytes, rawFilename);
        } finally {
            uploadSemaphore.release();
        }
    }

    private UploadDocumentResult doUploadDocument(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            byte[] bytes, String rawFilename
    ) {
        DocumentFileValidator.DetectedFile detected = DocumentFileValidator.detectFileType(bytes);
        String sha256 = DocumentFileValidator.computeSha256(bytes);
        String sanitizedFilename = DocumentFileValidator.sanitizeFilename(rawFilename, detected.extension());

        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRequestRecord request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (request.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot upload to a cancelled request");
        }

        PartyRecord party = partyStore.findById(workspaceId, appId, request.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));

        if (party.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        // Lock order: app -> party -> request
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new DocumentConflictException("app_archived", "App is archived or not found");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
            throw new DocumentNotFoundException("Document request not found");
        }

        // Re-read request after lock to get freshest version
        request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        // Check identical re-upload
        if (request.currentDocumentId() != null) {
            DocumentRecord currentDoc = documentStore.findById(workspaceId, appId, request.currentDocumentId()).orElse(null);
            if (currentDoc != null && sha256.equals(currentDoc.sha256())) {
                return new UploadDocumentResult(DocumentVersionResponse.from(currentDoc), true);
            }
        }

        // Check D-99 limits
        if (bytes.length > storageConfig.getMaxDocumentBytes()) {
            throw new DocumentConflictException("limit_exceeded", "File size exceeds limit (" + storageConfig.getMaxDocumentBytes() + " bytes)");
        }

        long currentVersions = documentStore.countVersionsByRequest(workspaceId, appId, requestId);
        if (currentVersions >= storageConfig.getMaxVersionsPerRequest()) {
            throw new DocumentConflictException("limit_exceeded", "Max versions per request reached (" + storageConfig.getMaxVersionsPerRequest() + ")");
        }

        long totalDocsInApp = documentStore.countTotalDocuments(workspaceId, appId);
        if (totalDocsInApp >= storageConfig.getMaxDocumentsPerApp()) {
            throw new DocumentConflictException("limit_exceeded", "Max documents per app reached (" + storageConfig.getMaxDocumentsPerApp() + ")");
        }

        long appStorageBytes = documentStore.sumStorageBytesApp(workspaceId, appId);
        if (appStorageBytes + bytes.length > storageConfig.getMaxStorageBytesPerApp()) {
            throw new DocumentConflictException("limit_exceeded", "App storage bytes limit exceeded");
        }

        long totalStorageBytes = documentStore.sumStorageBytesTotal();
        if (totalStorageBytes + bytes.length > storageConfig.getMaxStorageBytesTotal()) {
            throw new DocumentConflictException("storage_full", "Total storage bytes limit exceeded");
        }

        UUID docId = UUID.randomUUID();
        int newVersion = (int) currentVersions + 1;
        String storageKey = workspaceId + "/" + appId + "/" + docId;
        Instant now = clock.instant();

        // Put to storage first
        documentStorage.put(storageKey, bytes, detected.contentType());

        try {
            documentStore.insert(
                    docId, workspaceId, appId, request.partyId(), requestId, newVersion, storageKey, sanitizedFilename,
                    detected.contentType(), (long) bytes.length, sha256, userId, now
            );
            int rows = documentRequestStore.updateToReceived(workspaceId, appId, requestId, docId, request.version(), userId, now);
            if (rows == 0) {
                throw new DocumentConflictException("stale_version", "Document request version conflict");
            }
        } catch (Exception ex) {
            try {
                documentStorage.delete(storageKey);
            } catch (Exception ignored) {
            }
            throw ex;
        }

        DocumentRecord docRecord = documentStore.findById(workspaceId, appId, docId).orElseThrow();
        return new UploadDocumentResult(DocumentVersionResponse.from(docRecord), false);
    }

    @Transactional(readOnly = true)
    public DocumentDownload getDocumentDownload(UUID workspaceId, UUID appId, UUID docId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        DocumentRecord doc = documentStore.findById(workspaceId, appId, docId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        if (doc.purgedAt() != null) {
            throw new DocumentGoneException("Document has been purged");
        }

        InputStream stream = documentStorage.open(doc.storageKey());
        return new DocumentDownload(doc.originalFilename(), doc.contentType(), doc.sizeBytes(), stream);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionResponse> listVersions(UUID workspaceId, UUID appId, UUID requestId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        return documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();
    }
}
