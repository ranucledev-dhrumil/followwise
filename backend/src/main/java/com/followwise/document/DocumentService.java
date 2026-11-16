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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
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
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.documentRequestStore = documentRequestStore;
        this.documentStore = documentStore;
        this.documentStorage = documentStorage;
        this.partyStore = partyStore;
        this.appStore = appStore;
        this.accessService = accessService;
        this.storageConfig = storageConfig;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        var appRecord = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        LocalDate todayInApp = getTodayInApp(appId, appRecord.currentVersion());

        return documentRequestStore.findByParty(workspaceId, appId, partyId)
                .stream().map(r -> DocumentRequestResponse.from(r, todayInApp)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentRequestResponse> listRequests(
            UUID workspaceId, UUID appId, UUID userId,
            String status, Boolean overdue, Integer expiringWithinDays, UUID partyId,
            Integer rawPage, Integer rawSize
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        var appRecord = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        LocalDate todayInApp = getTodayInApp(appId, appRecord.currentVersion());

        // Validate status
        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase();
            if (!s.equals("pending") && !s.equals("received") && !s.equals("approved")
                    && !s.equals("rejected") && !s.equals("expired") && !s.equals("cancelled")) {
                throw new DocumentBadRequestException("Invalid status filter: " + status);
            }
        }

        // Validate expiringWithinDays
        if (expiringWithinDays != null) {
            if (expiringWithinDays < 0 || expiringWithinDays > 365) {
                throw new DocumentBadRequestException("expiringWithinDays must be between 0 and 365");
            }
        }

        int page = (rawPage == null) ? 0 : rawPage;
        int size = (rawSize == null) ? 20 : rawSize;

        if (page < 0) {
            throw new DocumentBadRequestException("Page must be >= 0");
        }
        if (size < 1 || size > 50) {
            throw new DocumentBadRequestException("Size must be between 1 and 50");
        }

        List<DocumentRequestRecord> records = documentRequestStore.findPage(
                workspaceId, appId, partyId, status, overdue, expiringWithinDays, todayInApp, page, size
        );
        long total = documentRequestStore.countFiltered(
                workspaceId, appId, partyId, status, overdue, expiringWithinDays, todayInApp
        );
        List<DocumentRequestResponse> items = records.stream()
                .map(r -> DocumentRequestResponse.from(r, todayInApp))
                .toList();

        return new PageResponse<>(items, page, size, total);
    }

    private LocalDate getTodayInApp(UUID appId, int version) {
        String tz = "UTC";
        try {
            var defVersion = appStore.findVersion(appId, version);
            if (defVersion.isPresent()) {
                JsonNode root = jsonMapper.readTree(defVersion.get().definition());
                tz = root.path("timezone").asText("UTC");
            }
        } catch (Exception ignored) {
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tz);
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }
        return LocalDate.ofInstant(clock.instant(), zoneId);
    }

    @Transactional(readOnly = true)
    public DocumentRequestResponse getRequest(UUID workspaceId, UUID appId, UUID requestId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.VIEW_DOCUMENTS);

        DocumentRequestRecord record = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        var appRecord = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        LocalDate todayInApp = getTodayInApp(appId, appRecord.currentVersion());

        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();

        return DocumentRequestResponse.from(record, versions, todayInApp);
    }

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
        // (2) Cheap checks outside any transaction
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

        if (bytes.length > storageConfig.getMaxDocumentBytes()) {
            throw new DocumentConflictException("limit_exceeded", "File size exceeds limit (" + storageConfig.getMaxDocumentBytes() + " bytes)");
        }

        // Pre-check identical re-upload outside transaction: skip put and return 200
        if (request.currentDocumentId() != null) {
            DocumentRecord currentDoc = documentStore.findById(workspaceId, appId, request.currentDocumentId()).orElse(null);
            if (currentDoc != null && sha256.equals(currentDoc.sha256())) {
                return new UploadDocumentResult(DocumentVersionResponse.from(currentDoc), true);
            }
        }

        // (3) storage.put under a new random key (outside transaction)
        UUID docId = UUID.randomUUID();
        String storageKey = workspaceId + "/" + appId + "/" + docId;
        documentStorage.put(storageKey, bytes, detected.contentType());

        // (4) One short transaction in TransactionTemplate
        try {
            return transactionTemplate.execute(status -> {
                // Lock app, then party, then request
                if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
                    throw new DocumentConflictException("app_archived", "App is archived or not found");
                }

                AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                        .orElseThrow(() -> new DocumentNotFoundException("App not found"));
                if (lockedApp.isArchived()) {
                    throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
                }

                if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
                    throw new DocumentNotFoundException("Party not found");
                }

                PartyRecord lockedParty = partyStore.findById(workspaceId, appId, request.partyId())
                        .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
                if (lockedParty.isArchived()) {
                    throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
                }

                if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
                    throw new DocumentNotFoundException("Document request not found");
                }

                DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, requestId)
                        .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

                if (lockedRequest.cancelledAt() != null) {
                    throw new DocumentConflictException("request_cancelled", "Cannot upload to a cancelled request");
                }

                // Repeat identical-bytes check: if now identical, delete object and return 200
                if (lockedRequest.currentDocumentId() != null) {
                    DocumentRecord currentDoc = documentStore.findById(workspaceId, appId, lockedRequest.currentDocumentId()).orElse(null);
                    if (currentDoc != null && sha256.equals(currentDoc.sha256())) {
                        try {
                            documentStorage.delete(storageKey);
                        } catch (Exception ignored) {
                        }
                        return new UploadDocumentResult(DocumentVersionResponse.from(currentDoc), true);
                    }
                }

                // Check every D-99 limit
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

                int newVersion = (int) currentVersions + 1;
                Instant now = clock.instant();

                documentStore.insert(
                        docId, workspaceId, appId, lockedRequest.partyId(), requestId, newVersion, storageKey, sanitizedFilename,
                        detected.contentType(), (long) bytes.length, sha256, userId, now
                );
                int rows = documentRequestStore.updateToReceived(workspaceId, appId, requestId, docId, lockedRequest.version(), userId, now);
                if (rows == 0) {
                    throw new DocumentConflictException("stale_version", "Document request version conflict");
                }

                DocumentRecord docRecord = documentStore.findById(workspaceId, appId, docId).orElseThrow();
                return new UploadDocumentResult(DocumentVersionResponse.from(docRecord), false);
            });
        } catch (Exception ex) {
            // (5) On any failure after the put, delete the object best effort and rethrow
            try {
                documentStorage.delete(storageKey);
            } catch (Exception ignored) {
            }
            throw ex;
        }
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

    @Transactional
    public DocumentRequestResponse approveRequest(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            int expectedVersion, LocalDate bodyExpiresOn
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRequestRecord request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (request.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
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

        AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        if (lockedApp.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        PartyRecord lockedParty = partyStore.findById(workspaceId, appId, request.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
        if (lockedParty.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
            throw new DocumentNotFoundException("Document request not found");
        }

        DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (lockedRequest.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
        }

        if (!"RECEIVED".equals(lockedRequest.status())) {
            throw new DocumentConflictException("invalid_state", "Can only approve a request in RECEIVED status");
        }

        ZoneId zoneId = getAppZoneId(appId, lockedApp.currentVersion());
        LocalDate todayInApp = LocalDate.ofInstant(clock.instant(), zoneId);

        LocalDate finalExpiresOn;
        if (bodyExpiresOn != null) {
            if (bodyExpiresOn.isBefore(LocalDate.of(2000, 1, 1))) {
                throw new DocumentBadRequestException("expiresOn must not be before 2000-01-01");
            }
            if (bodyExpiresOn.isAfter(todayInApp.plusYears(20))) {
                throw new DocumentBadRequestException("expiresOn must not be more than 20 years in future");
            }
            finalExpiresOn = bodyExpiresOn;
        } else if (lockedRequest.expiryDays() != null) {
            finalExpiresOn = todayInApp.plusDays(lockedRequest.expiryDays());
        } else {
            finalExpiresOn = null;
        }

        Instant now = clock.instant();
        int rows = documentRequestStore.updateToApproved(workspaceId, appId, requestId, finalExpiresOn, expectedVersion, userId, now);
        if (rows == 0) {
            DocumentRequestRecord current = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
            throw new DocumentConflictException("stale_version", "Document request version conflict", current.version());
        }

        DocumentRequestRecord updated = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();
        return DocumentRequestResponse.from(updated, versions);
    }

    @Transactional
    public DocumentRequestResponse rejectRequest(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            int expectedVersion, String reason
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRequestRecord request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (request.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
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

        AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        if (lockedApp.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        PartyRecord lockedParty = partyStore.findById(workspaceId, appId, request.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
        if (lockedParty.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
            throw new DocumentNotFoundException("Document request not found");
        }

        DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (lockedRequest.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
        }

        if (!"RECEIVED".equals(lockedRequest.status())) {
            throw new DocumentConflictException("invalid_state", "Can only reject a request in RECEIVED status");
        }

        Instant now = clock.instant();
        int rows = documentRequestStore.updateToRejected(workspaceId, appId, requestId, reason, expectedVersion, userId, now);
        if (rows == 0) {
            DocumentRequestRecord current = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
            throw new DocumentConflictException("stale_version", "Document request version conflict", current.version());
        }

        DocumentRequestRecord updated = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();
        return DocumentRequestResponse.from(updated, versions);
    }

    @Transactional
    public DocumentRequestResponse updateDueDate(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            int expectedVersion, LocalDate bodyDueDate
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRequestRecord request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (request.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
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

        AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        if (lockedApp.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        PartyRecord lockedParty = partyStore.findById(workspaceId, appId, request.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
        if (lockedParty.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
            throw new DocumentNotFoundException("Document request not found");
        }

        DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (lockedRequest.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Cannot modify a cancelled request");
        }

        LocalDate finalDueDate;
        if (bodyDueDate != null) {
            ZoneId zoneId = getAppZoneId(appId, lockedApp.currentVersion());
            LocalDate todayInApp = LocalDate.ofInstant(clock.instant(), zoneId);
            if (bodyDueDate.isBefore(todayInApp.minusYears(5)) || bodyDueDate.isAfter(todayInApp.plusYears(5))) {
                throw new DocumentBadRequestException("dueDate must be within 5 years before and after today");
            }
            finalDueDate = bodyDueDate;
        } else {
            finalDueDate = null;
        }

        Instant now = clock.instant();
        int rows = documentRequestStore.updateDueDate(workspaceId, appId, requestId, finalDueDate, expectedVersion, userId, now);
        if (rows == 0) {
            DocumentRequestRecord current = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
            throw new DocumentConflictException("stale_version", "Document request version conflict", current.version());
        }

        DocumentRequestRecord updated = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();
        return DocumentRequestResponse.from(updated, versions);
    }

    @Transactional
    public DocumentRequestResponse cancelRequest(
            UUID workspaceId, UUID appId, UUID requestId, UUID userId,
            int expectedVersion
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRequestRecord request = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (request.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Request is already cancelled");
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

        AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));
        if (lockedApp.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, request.partyId()).isEmpty()) {
            throw new DocumentNotFoundException("Party not found");
        }

        PartyRecord lockedParty = partyStore.findById(workspaceId, appId, request.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
        if (lockedParty.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        if (documentRequestStore.lockRequestRow(workspaceId, appId, requestId).isEmpty()) {
            throw new DocumentNotFoundException("Document request not found");
        }

        DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

        if (lockedRequest.cancelledAt() != null) {
            throw new DocumentConflictException("request_cancelled", "Request is already cancelled");
        }

        Instant now = clock.instant();
        int rows = documentRequestStore.updateToCancelled(workspaceId, appId, requestId, expectedVersion, userId, now);
        if (rows == 0) {
            DocumentRequestRecord current = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
            throw new DocumentConflictException("stale_version", "Document request version conflict", current.version());
        }

        DocumentRequestRecord updated = documentRequestStore.findById(workspaceId, appId, requestId).orElseThrow();
        List<DocumentVersionResponse> versions = documentStore.findVersionsByRequest(workspaceId, appId, requestId)
                .stream().map(DocumentVersionResponse::from).toList();
        return DocumentRequestResponse.from(updated, versions);
    }

    public DocumentVersionResponse purgeDocument(UUID workspaceId, UUID appId, UUID documentId, UUID userId) {
        accessService.requirePermission(workspaceId, userId, Permission.MANAGE_DOCUMENTS);

        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException("App not found"));

        if (app.isArchived()) {
            throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
        }

        DocumentRecord doc = documentStore.findById(workspaceId, appId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        PartyRecord party = partyStore.findById(workspaceId, appId, doc.partyId())
                .orElseThrow(() -> new DocumentNotFoundException("Party not found"));

        if (party.isArchived()) {
            throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
        }

        String storageKeyToDelete = transactionTemplate.execute(status -> {
            // Lock order: app -> party -> request
            if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
                throw new DocumentConflictException("app_archived", "App is archived or not found");
            }

            AppRecord lockedApp = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                    .orElseThrow(() -> new DocumentNotFoundException("App not found"));
            if (lockedApp.isArchived()) {
                throw new DocumentConflictException("app_archived", "Cannot modify an archived app");
            }

            if (partyStore.lockPartyRow(workspaceId, appId, doc.partyId()).isEmpty()) {
                throw new DocumentNotFoundException("Party not found");
            }

            PartyRecord lockedParty = partyStore.findById(workspaceId, appId, doc.partyId())
                    .orElseThrow(() -> new DocumentNotFoundException("Party not found"));
            if (lockedParty.isArchived()) {
                throw new DocumentConflictException("party_archived", "Cannot modify an archived party");
            }

            if (documentRequestStore.lockRequestRow(workspaceId, appId, doc.requestId()).isEmpty()) {
                throw new DocumentNotFoundException("Document request not found");
            }

            DocumentRequestRecord lockedRequest = documentRequestStore.findById(workspaceId, appId, doc.requestId())
                    .orElseThrow(() -> new DocumentNotFoundException("Document request not found"));

            if (documentId.equals(lockedRequest.currentDocumentId())) {
                throw new DocumentConflictException("current_document", "Cannot purge the current version of a document request");
            }

            DocumentRecord lockedDoc = documentStore.findById(workspaceId, appId, documentId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

            if (lockedDoc.purgedAt() != null) {
                throw new DocumentConflictException("already_purged", "Document has already been purged");
            }

            Instant now = clock.instant();
            int rows = documentStore.purge(workspaceId, appId, documentId, now);
            if (rows == 0) {
                throw new DocumentConflictException("already_purged", "Document has already been purged");
            }

            return lockedDoc.storageKey();
        });

        if (storageKeyToDelete != null) {
            try {
                documentStorage.delete(storageKeyToDelete);
            } catch (Exception ignored) {
            }
        }

        DocumentRecord purgedDoc = documentStore.findById(workspaceId, appId, documentId).orElseThrow();
        return DocumentVersionResponse.from(purgedDoc);
    }

    private ZoneId getAppZoneId(UUID appId, int version) {
        AppDefinitionVersionRecord defVersion = appStore.findVersion(appId, version)
                .orElse(null);
        if (defVersion == null) {
            return ZoneId.of("UTC");
        }
        try {
            JsonNode root = jsonMapper.readTree(defVersion.definition());
            String tz = root.path("timezone").asText("UTC");
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
