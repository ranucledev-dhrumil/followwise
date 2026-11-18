package com.followwise.reminder;

import com.followwise.app.AppStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = {"app.scheduler-enabled", "app.reminders-enabled"}, havingValue = "true")
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final AppStore appStore;
    private final ReminderEngineService reminderEngineService;
    private final ReminderEventStore reminderEventStore;
    private final ReminderProperties reminderProperties;

    public ReminderScheduler(
            AppStore appStore,
            ReminderEngineService reminderEngineService,
            ReminderEventStore reminderEventStore,
            ReminderProperties reminderProperties
    ) {
        this.appStore = appStore;
        this.reminderEngineService = reminderEngineService;
        this.reminderEventStore = reminderEventStore;
        this.reminderProperties = reminderProperties;
    }

    @Scheduled(
            fixedDelayString = "#{T(java.lang.Math).max(5, ${app.reminder.interval-minutes:15}) * 60000}",
            initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextInt(30000)}"
    )
    public void runAll() {
        long queuedOutbox = reminderEventStore.countQueuedOutboxTotal();
        if (queuedOutbox > reminderProperties.getMaxOutboxBacklog()) {
            log.warn("Reminder scheduler run blocked by outbox backlog: queued={}", queuedOutbox);
            return;
        }

        List<AppStore.WorkspaceAppId> activeApps = appStore.findActiveAppIds();
        int totalApps = activeApps.size();
        int totalQueued = 0;
        int totalSkippedUnverifiedOwner = 0;
        int totalSkippedNoEmail = 0;
        int totalSkippedPaused = 0;
        int totalSkippedRateLimit = 0;
        int totalSkippedExisting = 0;
        int totalSkippedLocked = 0;
        int totalFailed = 0;

        for (AppStore.WorkspaceAppId appRef : activeApps) {
            try {
                ReminderEngineService.ReminderProcessResult res = reminderEngineService.processApp(
                        appRef.workspaceId(),
                        appRef.appId(),
                        false,
                        true
                );
                totalQueued += res.queued();
                totalSkippedUnverifiedOwner += res.skippedUnverifiedOwner();
                totalSkippedNoEmail += res.skippedNoEmail();
                totalSkippedPaused += res.skippedPaused();
                totalSkippedRateLimit += res.skippedRateLimit();
                totalSkippedExisting += res.skippedExisting();
                totalSkippedLocked += res.skippedLocked();
            } catch (Exception e) {
                totalFailed++;
                log.error("Failed to process reminders for app {}: {}", appRef.appId(), e.getClass().getSimpleName());
            }
        }

        log.info("Reminder scheduler run completed: appsProcessed={}, queued={}, skippedUnverifiedOwner={}, skippedNoEmail={}, skippedPaused={}, skippedRateLimit={}, skippedExisting={}, skippedLocked={}, failed={}",
                totalApps, totalQueued, totalSkippedUnverifiedOwner, totalSkippedNoEmail, totalSkippedPaused, totalSkippedRateLimit, totalSkippedExisting, totalSkippedLocked, totalFailed);
    }
}
