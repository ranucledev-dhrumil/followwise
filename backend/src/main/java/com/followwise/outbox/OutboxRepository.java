package com.followwise.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    @Query(value = """
            SELECT * FROM email_outbox
            WHERE status = 'QUEUED' AND send_after <= :now
            ORDER BY
                CASE
                    WHEN kind IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'PASSWORD_CHANGED') THEN 0
                    ELSE 1
                END ASC,
                created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailOutbox> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    @Query("SELECT COUNT(e) FROM EmailOutbox e WHERE e.status = 'SENT' AND e.sentAt >= :since")
    long countSentSince(@Param("since") Instant since);
}
