package com.followwise.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduler-enabled", havingValue = "true")
public class OutboxScheduler {

    private final OutboxService outboxService;

    public OutboxScheduler(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelay = 60000)
    public void processOutbox() {
        outboxService.processOutboxBatch();
    }
}
