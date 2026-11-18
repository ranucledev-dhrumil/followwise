package com.followwise.reminder;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ReminderEventStore {

    private final JdbcClient jdbcClient;

    public record ReminderEventRecord(
            UUID id,
            UUID workspaceId,
            UUID appId,
            UUID partyId,
            String ruleId,
            UUID subjectId,
            String recipientKind,
            UUID recipientUserId,
            LocalDate scheduledFor,
            String status,
            String skipReason,
            UUID outboxId,
            String messageKey,
            Instant createdAt
    ) {}

    private static final RowMapper<ReminderEventRecord> ROW_MAPPER = (rs, rowNum) -> new ReminderEventRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("app_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getString("rule_id"),
            rs.getObject("subject_id", UUID.class),
            rs.getString("recipient_kind"),
            rs.getObject("recipient_user_id", UUID.class),
            rs.getObject("scheduled_for", LocalDate.class),
            rs.getString("status"),
            rs.getString("skip_reason"),
            rs.getObject("outbox_id", UUID.class),
            rs.getString("message_key"),
            rs.getTimestamp("created_at").toInstant()
    );

    public ReminderEventStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int insertEvent(
            UUID id,
            UUID workspaceId,
            UUID appId,
            UUID partyId,
            String ruleId,
            UUID subjectId,
            String recipientKind,
            UUID recipientUserId,
            LocalDate scheduledFor,
            String status,
            String skipReason,
            UUID outboxId,
            String messageKey,
            Instant now
    ) {
        return jdbcClient.sql("""
                INSERT INTO reminder_events (
                    id, workspace_id, app_id, party_id, rule_id, subject_id,
                    recipient_kind, recipient_user_id, scheduled_for, status,
                    skip_reason, outbox_id, message_key, created_at
                ) VALUES (
                    :id, :workspaceId, :appId, :partyId, :ruleId, :subjectId,
                    :recipientKind, :recipientUserId, :scheduledFor, :status,
                    :skipReason, :outboxId, :messageKey, :createdAt
                )
                ON CONFLICT (
                    app_id, rule_id, COALESCE(subject_id, '00000000-0000-0000-0000-000000000000'),
                    recipient_kind, COALESCE(recipient_user_id, '00000000-0000-0000-0000-000000000000'),
                    scheduled_for
                ) DO NOTHING
                """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("appId", appId)
                .param("partyId", partyId)
                .param("ruleId", ruleId)
                .param("subjectId", subjectId)
                .param("recipientKind", recipientKind)
                .param("recipientUserId", recipientUserId)
                .param("scheduledFor", scheduledFor)
                .param("status", status)
                .param("skipReason", skipReason)
                .param("outboxId", outboxId)
                .param("messageKey", messageKey)
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    public boolean existsEvent(
            UUID appId,
            String ruleId,
            UUID subjectId,
            String recipientKind,
            UUID recipientUserId,
            LocalDate scheduledFor
    ) {
        return jdbcClient.sql("""
                SELECT 1 FROM reminder_events
                WHERE app_id = :aid
                  AND rule_id = :ruleId
                  AND COALESCE(subject_id, '00000000-0000-0000-0000-000000000000'::uuid) = COALESCE(:subjectId::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
                  AND recipient_kind = :recipientKind
                  AND COALESCE(recipient_user_id, '00000000-0000-0000-0000-000000000000'::uuid) = COALESCE(:recipientUserId::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
                  AND scheduled_for = :scheduledFor
                """)
                .param("aid", appId)
                .param("ruleId", ruleId)
                .param("subjectId", subjectId)
                .param("recipientKind", recipientKind)
                .param("recipientUserId", recipientUserId)
                .param("scheduledFor", scheduledFor)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public long countQueuedPartyToday(UUID appId, UUID partyId, LocalDate appLocalToday, String timezone) {
        return jdbcClient.sql("""
                SELECT count(*) FROM reminder_events
                WHERE app_id = :aid
                  AND party_id = :partyId
                  AND recipient_kind = 'PARTY'
                  AND status = 'QUEUED'
                  AND (created_at AT TIME ZONE :tz)::date = :appLocalToday
                """)
                .param("aid", appId)
                .param("partyId", partyId)
                .param("tz", timezone)
                .param("appLocalToday", appLocalToday)
                .query(Long.class)
                .single();
    }

    public long countQueuedOwnerToday(UUID appId, UUID recipientUserId, LocalDate appLocalToday, String timezone) {
        return jdbcClient.sql("""
                SELECT count(*) FROM reminder_events
                WHERE app_id = :aid
                  AND recipient_user_id = :userId
                  AND recipient_kind = 'OWNER'
                  AND status = 'QUEUED'
                  AND (created_at AT TIME ZONE :tz)::date = :appLocalToday
                """)
                .param("aid", appId)
                .param("userId", recipientUserId)
                .param("tz", timezone)
                .param("appLocalToday", appLocalToday)
                .query(Long.class)
                .single();
    }

    public long countQueuedOutboxTotal() {
        return jdbcClient.sql("SELECT count(*) FROM email_outbox WHERE status = 'QUEUED'")
                .query(Long.class)
                .single();
    }

    public void insertOutboxReminder(
            UUID id,
            String toAddress,
            String subject,
            String bodyText,
            String replyTo,
            Instant now
    ) {
        jdbcClient.sql("""
                INSERT INTO email_outbox (
                    id, kind, to_address, subject, body_text, reply_to, status, attempts, send_after, created_at
                ) VALUES (
                    :id, 'REMINDER', :toAddress, :subject, :bodyText, :replyTo, 'QUEUED', 0, :sendAfter, :createdAt
                )
                """)
                .param("id", id)
                .param("toAddress", toAddress)
                .param("subject", subject)
                .param("bodyText", bodyText)
                .param("replyTo", replyTo)
                .param("sendAfter", Timestamp.from(now))
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    public List<ReminderEventRecord> findHistory(
            UUID workspaceId,
            UUID appId,
            UUID partyId,
            String status,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, workspace_id, app_id, party_id, rule_id, subject_id,
                       recipient_kind, recipient_user_id, scheduled_for, status,
                       skip_reason, outbox_id, message_key, created_at
                FROM reminder_events
                WHERE workspace_id = :wid AND app_id = :aid
                """);

        if (partyId != null) {
            sql.append(" AND party_id = :pid");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");

        var query = jdbcClient.sql(sql.toString())
                .param("wid", workspaceId)
                .param("aid", appId)
                .param("limit", size)
                .param("offset", page * size);

        if (partyId != null) {
            query.param("pid", partyId);
        }
        if (status != null && !status.isBlank()) {
            query.param("status", status);
        }

        return query.query(ROW_MAPPER).list();
    }
}
