package com.followwise.reminder;

import com.followwise.app.AppRecord;
import com.followwise.app.AppStore;
import com.followwise.party.PartyRecord;
import com.followwise.party.PartyResponse;
import com.followwise.party.PartyService;
import com.followwise.party.PartyStore;
import com.followwise.workspace.AccessService;
import com.followwise.workspace.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReminderService {

    private final AccessService accessService;
    private final AppStore appStore;
    private final PartyStore partyStore;
    private final PartyService partyService;
    private final Clock clock;

    public ReminderService(
            AccessService accessService,
            AppStore appStore,
            PartyStore partyStore,
            PartyService partyService,
            Clock clock
    ) {
        this.accessService = accessService;
        this.appStore = appStore;
        this.partyStore = partyStore;
        this.partyService = partyService;
        this.clock = clock;
    }

    @Transactional
    public PartyResponse pauseReminders(
            UUID workspaceId, UUID appId, UUID partyId, int expectedVersion, UUID userId
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        // Lock order: app -> party
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new ReminderConflictException("app_archived", "App is archived or not found");
        }
        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new ReminderNotFoundException("App not found"));
        if (app.isArchived()) {
            throw new ReminderConflictException("app_archived", "App is archived");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, partyId).isEmpty()) {
            throw new ReminderNotFoundException("Party not found");
        }
        PartyRecord party = partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new ReminderNotFoundException("Party not found"));
        if (party.isArchived()) {
            throw new ReminderConflictException("party_archived", "Party is archived");
        }

        if (party.version() != expectedVersion) {
            throw new ReminderConflictException("stale_version", "Party version conflict", party.version());
        }

        if (party.isPaused()) {
            return partyService.getParty(workspaceId, appId, partyId, userId);
        }

        Instant now = clock.instant();
        int affected = partyStore.pauseReminders(workspaceId, appId, partyId, expectedVersion, now);
        if (affected == 0) {
            PartyRecord current = partyStore.findById(workspaceId, appId, partyId).orElseThrow();
            throw new ReminderConflictException("stale_version", "Party version conflict", current.version());
        }

        return partyService.getParty(workspaceId, appId, partyId, userId);
    }

    @Transactional
    public PartyResponse resumeReminders(
            UUID workspaceId, UUID appId, UUID partyId, int expectedVersion, UUID userId
    ) {
        accessService.requirePermission(workspaceId, userId, Permission.EDIT_DATA);

        // Lock order: app -> party
        if (partyStore.lockApp(workspaceId, appId).isEmpty()) {
            throw new ReminderConflictException("app_archived", "App is archived or not found");
        }
        AppRecord app = appStore.findAppByIdAndWorkspaceId(appId, workspaceId)
                .orElseThrow(() -> new ReminderNotFoundException("App not found"));
        if (app.isArchived()) {
            throw new ReminderConflictException("app_archived", "App is archived");
        }

        if (partyStore.lockPartyRow(workspaceId, appId, partyId).isEmpty()) {
            throw new ReminderNotFoundException("Party not found");
        }
        PartyRecord party = partyStore.findById(workspaceId, appId, partyId)
                .orElseThrow(() -> new ReminderNotFoundException("Party not found"));
        if (party.isArchived()) {
            throw new ReminderConflictException("party_archived", "Party is archived");
        }

        if (party.version() != expectedVersion) {
            throw new ReminderConflictException("stale_version", "Party version conflict", party.version());
        }

        if (!party.isPaused()) {
            return partyService.getParty(workspaceId, appId, partyId, userId);
        }

        Instant now = clock.instant();
        int affected = partyStore.resumeReminders(workspaceId, appId, partyId, expectedVersion, now);
        if (affected == 0) {
            PartyRecord current = partyStore.findById(workspaceId, appId, partyId).orElseThrow();
            throw new ReminderConflictException("stale_version", "Party version conflict", current.version());
        }

        return partyService.getParty(workspaceId, appId, partyId, userId);
    }
}
