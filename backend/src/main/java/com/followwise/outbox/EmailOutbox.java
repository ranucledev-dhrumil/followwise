package com.followwise.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    public static final String KIND_VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String KIND_RESET_PASSWORD = "RESET_PASSWORD";
    public static final String KIND_PASSWORD_CHANGED = "PASSWORD_CHANGED";

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(nullable = false)
    private String subject;

    @Column(name = "body_text", nullable = false)
    private String bodyText;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "send_after", nullable = false)
    private Instant sendAfter;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EmailOutbox() {
    }

    public EmailOutbox(UUID id, String kind, String toAddress, String subject, String bodyText, Instant now) {
        this.id = id;
        this.kind = kind;
        this.toAddress = toAddress;
        this.subject = subject;
        this.bodyText = bodyText;
        this.status = STATUS_QUEUED;
        this.attempts = 0;
        this.lastError = null;
        this.sendAfter = now;
        this.sentAt = null;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getSendAfter() {
        return sendAfter;
    }

    public void setSendAfter(Instant sendAfter) {
        this.sendAfter = sendAfter;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
