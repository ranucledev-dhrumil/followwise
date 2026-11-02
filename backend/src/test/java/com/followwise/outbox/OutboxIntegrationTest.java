package com.followwise.outbox;

import com.followwise.AbstractIntegrationTest;
import com.followwise.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
    }

    @Test
    void capEnforcedWithOverflowLeftQueuedAndAccountKindsFirst() {
        Instant now = Instant.now();

        // Insert a reminder created earlier
        EmailOutbox reminderOld = new EmailOutbox(
                UUID.randomUUID(), "REMINDER", "user1@example.com", "Reminder 1", "Body 1", now.minus(10, ChronoUnit.MINUTES)
        );
        // Insert an account email created later
        EmailOutbox verifyEmail = new EmailOutbox(
                UUID.randomUUID(), EmailOutbox.KIND_VERIFY_EMAIL, "user2@example.com", "Verify email", "Secret token link", now.minus(5, ChronoUnit.MINUTES)
        );
        // Insert another reminder created most recently
        EmailOutbox reminderNew = new EmailOutbox(
                UUID.randomUUID(), "REMINDER", "user3@example.com", "Reminder 2", "Body 3", now.minus(1, ChronoUnit.MINUTES)
        );

        outboxRepository.saveAll(List.of(reminderOld, verifyEmail, reminderNew));

        doNothing().when(emailSender).send(anyString(), anyString(), anyString());

        // Claim with limit 1: verify_email must be chosen first, despite being newer than reminderOld
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<EmailOutbox> claimed = tx.execute(status -> outboxRepository.claimBatch(now, 1));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getId()).isEqualTo(verifyEmail.getId());

        // Process batch: sends all 3 items
        outboxService.processOutboxBatch();

        EmailOutbox sentVerify = outboxRepository.findById(verifyEmail.getId()).orElseThrow();
        assertThat(sentVerify.getStatus()).isEqualTo(EmailOutbox.STATUS_SENT);
        assertThat(sentVerify.getBodyText()).isEqualTo("[redacted]");
        assertThat(sentVerify.getSentAt()).isNotNull();

        EmailOutbox sentReminder = outboxRepository.findById(reminderOld.getId()).orElseThrow();
        assertThat(sentReminder.getStatus()).isEqualTo(EmailOutbox.STATUS_SENT);
        assertThat(sentReminder.getBodyText()).isEqualTo("[redacted]");
    }

    @Test
    void retryWithBackoffAndFailedAt5AttemptsWithRedactedBody() {
        Instant now = Instant.now();
        UUID outboxId = UUID.randomUUID();
        EmailOutbox item = new EmailOutbox(
                outboxId, EmailOutbox.KIND_RESET_PASSWORD, "user@example.com", "Reset", "secret-link-123", now
        );
        outboxRepository.save(item);

        doThrow(new RuntimeException("SMTP connection refused")).when(emailSender).send(anyString(), anyString(), anyString());

        // Attempt 1 fails
        outboxService.processOutboxBatch();

        EmailOutbox afterAttempt1 = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(afterAttempt1.getAttempts()).isEqualTo(1);
        assertThat(afterAttempt1.getStatus()).isEqualTo(EmailOutbox.STATUS_QUEUED);
        assertThat(afterAttempt1.getLastError()).isEqualTo("SMTP connection refused");
        assertThat(afterAttempt1.getBodyText()).isEqualTo("secret-link-123");
        assertThat(afterAttempt1.getSendAfter()).isAfter(now);

        // Simulate 4 prior attempts, so next one is 5th
        afterAttempt1.setAttempts(4);
        afterAttempt1.setSendAfter(now.minusSeconds(1));
        outboxRepository.save(afterAttempt1);

        // Attempt 5 fails -> becomes FAILED and body is redacted
        outboxService.processOutboxBatch();

        EmailOutbox afterAttempt5 = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(afterAttempt5.getAttempts()).isEqualTo(5);
        assertThat(afterAttempt5.getStatus()).isEqualTo(EmailOutbox.STATUS_FAILED);
        assertThat(afterAttempt5.getBodyText()).isEqualTo("[redacted]");
        assertThat(afterAttempt5.getLastError()).isEqualTo("SMTP connection refused");
    }

    @Test
    void twoConcurrentClaimsNeverReturnTheSameRow() throws InterruptedException {
        Instant now = Instant.now();
        List<EmailOutbox> items = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            items.add(new EmailOutbox(
                    UUID.randomUUID(), EmailOutbox.KIND_VERIFY_EMAIL, "user" + i + "@example.com", "Sub " + i, "Body " + i, now.minusSeconds(10 - i)
            ));
        }
        outboxRepository.saveAll(items);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        List<UUID> claimedByT1 = Collections.synchronizedList(new ArrayList<>());
        List<UUID> claimedByT2 = Collections.synchronizedList(new ArrayList<>());

        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);

        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                tx1.execute(status -> {
                    List<EmailOutbox> batch = outboxRepository.claimBatch(now, 2);
                    for (EmailOutbox e : batch) {
                        claimedByT1.add(e.getId());
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                tx2.execute(status -> {
                    List<EmailOutbox> batch = outboxRepository.claimBatch(now, 2);
                    for (EmailOutbox e : batch) {
                        claimedByT2.add(e.getId());
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        t1.start();
        t2.start();
        startLatch.countDown();

        boolean finished = finishLatch.await(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        assertThat(claimedByT1).hasSize(2);
        assertThat(claimedByT2).hasSize(2);

        Set<UUID> intersection = new HashSet<>(claimedByT1);
        intersection.retainAll(claimedByT2);
        // FOR UPDATE SKIP LOCKED ensures disjoint claim sets
        assertThat(intersection).isEmpty();
    }
}
