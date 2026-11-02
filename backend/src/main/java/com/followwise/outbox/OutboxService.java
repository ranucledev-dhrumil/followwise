package com.followwise.outbox;

import com.followwise.email.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final EmailSender emailSender;

    @Value("${mail.daily-cap:400}")
    private int mailDailyCap;

    public OutboxService(OutboxRepository outboxRepository, EmailSender emailSender) {
        this.outboxRepository = outboxRepository;
        this.emailSender = emailSender;
    }

    @Transactional
    public int processOutboxBatch() {
        Instant now = Instant.now();
        long sentLast24h = outboxRepository.countSentSince(now.minus(24, ChronoUnit.HOURS));
        int remainingCap = (int) Math.max(0, mailDailyCap - sentLast24h);

        if (remainingCap <= 0) {
            return 0;
        }

        int batchSize = Math.min(10, remainingCap);
        List<EmailOutbox> batch = outboxRepository.claimBatch(now, batchSize);

        for (EmailOutbox item : batch) {
            try {
                emailSender.send(item.getToAddress(), item.getSubject(), item.getBodyText());
                item.setStatus(EmailOutbox.STATUS_SENT);
                item.setSentAt(now);
                item.setBodyText("[redacted]");
                item.setLastError(null);
            } catch (Exception e) {
                int attempts = item.getAttempts() + 1;
                item.setAttempts(attempts);
                item.setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                if (attempts >= 5) {
                    item.setStatus(EmailOutbox.STATUS_FAILED);
                    item.setBodyText("[redacted]");
                } else {
                    long backoffMinutes = (long) Math.pow(2, attempts);
                    item.setSendAfter(now.plus(Duration.ofMinutes(backoffMinutes)));
                }
            }
            outboxRepository.save(item);
        }

        return batch.size();
    }
}
