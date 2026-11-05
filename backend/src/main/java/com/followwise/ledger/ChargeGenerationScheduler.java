package com.followwise.ledger;

import com.followwise.app.AppStore;
import com.followwise.identity.SystemUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "app.scheduler-enabled", havingValue = "true")
public class ChargeGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChargeGenerationScheduler.class);

    private final AppStore appStore;
    private final ChargeGeneratorService chargeGeneratorService;

    public ChargeGenerationScheduler(AppStore appStore, ChargeGeneratorService chargeGeneratorService) {
        this.appStore = appStore;
        this.chargeGeneratorService = chargeGeneratorService;
    }

    @Scheduled(
            fixedDelayString = "#{T(java.lang.Math).max(5, ${app.charge.generation-interval-minutes:60}) * 60000}",
            initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextInt(30000)}"
    )
    public void runAll() {
        List<AppStore.WorkspaceAppId> activeApps = appStore.findActiveAppIds();
        int totalApps = activeApps.size();
        int totalCreated = 0;
        int totalSkippedExisting = 0;
        int totalSkippedInvalid = 0;
        int totalSkippedLocked = 0;
        int totalFailed = 0;

        for (AppStore.WorkspaceAppId appRef : activeApps) {
            try {
                ChargeGenerationResult result = chargeGeneratorService.generateForApp(
                        appRef.workspaceId(),
                        appRef.appId(),
                        SystemUser.ID,
                        false,
                        true
                );
                totalCreated += result.created();
                totalSkippedExisting += result.skippedExisting();
                totalSkippedInvalid += result.skippedInvalid();
                totalSkippedLocked += result.skippedLocked();
                totalFailed += result.failed();
            } catch (Exception e) {
                totalFailed++;
                log.error("Failed to generate charges for app {}: {}", appRef.appId(), e.getMessage());
            }
        }

        log.info("Charge generation scheduler run completed: appsProcessed={}, created={}, skippedExisting={}, skippedInvalid={}, skippedLocked={}, failed={}",
                totalApps, totalCreated, totalSkippedExisting, totalSkippedInvalid, totalSkippedLocked, totalFailed);
    }
}
