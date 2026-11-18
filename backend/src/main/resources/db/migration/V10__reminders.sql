-- V10: Reminders engine

ALTER TABLE parties ADD COLUMN reminders_paused_at TIMESTAMPTZ NULL;

ALTER TABLE email_outbox ADD COLUMN reply_to TEXT NULL;

CREATE TABLE reminder_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    party_id uuid NOT NULL,
    rule_id text NOT NULL,
    subject_id uuid NULL,
    recipient_kind text NOT NULL,
    recipient_user_id uuid NULL,
    scheduled_for date NOT NULL,
    status text NOT NULL,
    skip_reason text NULL,
    outbox_id uuid NULL REFERENCES email_outbox(id) ON DELETE RESTRICT,
    message_key text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_reminder_events_party FOREIGN KEY (party_id, workspace_id, app_id)
        REFERENCES parties(id, workspace_id, app_id) ON DELETE RESTRICT,
    CONSTRAINT chk_re_recipient_kind CHECK (recipient_kind IN ('PARTY', 'OWNER')),
    CONSTRAINT chk_re_status CHECK (status IN ('QUEUED', 'SKIPPED')),
    CONSTRAINT chk_re_skip CHECK ((status = 'SKIPPED' AND skip_reason IS NOT NULL) OR (status = 'QUEUED' AND skip_reason IS NULL)),
    CONSTRAINT chk_re_outbox CHECK ((status = 'QUEUED' AND outbox_id IS NOT NULL) OR (status = 'SKIPPED' AND outbox_id IS NULL))
);

CREATE UNIQUE INDEX idx_reminder_events_idempotent ON reminder_events (
    app_id,
    rule_id,
    COALESCE(subject_id, '00000000-0000-0000-0000-000000000000'),
    recipient_kind,
    COALESCE(recipient_user_id, '00000000-0000-0000-0000-000000000000'),
    scheduled_for
);

CREATE INDEX idx_reminder_events_app_party_status ON reminder_events (app_id, party_id, status);
CREATE INDEX idx_reminder_events_workspace ON reminder_events (workspace_id);

-- Immutability triggers for reminder_events
CREATE OR REPLACE FUNCTION prevent_reminder_events_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reminder_events is immutable: UPDATE, DELETE and TRUNCATE are not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reminder_events_no_update
    BEFORE UPDATE ON reminder_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_reminder_events_modification();

CREATE TRIGGER trg_reminder_events_no_delete
    BEFORE DELETE ON reminder_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_reminder_events_modification();

CREATE TRIGGER trg_reminder_events_no_truncate
    BEFORE TRUNCATE ON reminder_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_reminder_events_modification();
