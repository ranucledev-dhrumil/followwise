-- Add UNIQUE constraint on parties for composite FK referencing
ALTER TABLE parties ADD CONSTRAINT uq_parties_id_workspace_app UNIQUE (id, workspace_id, app_id);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    party_id uuid NOT NULL,
    charge_id uuid NOT NULL,
    entry_type text NOT NULL,
    amount_cents bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'USD',
    kind text NULL,
    schedule_id text NULL,
    period_key text NULL,
    label text NULL,
    due_date date NULL,
    paid_on date NULL,
    note text NULL,
    reverses_entry_id uuid NULL UNIQUE,
    idempotency_key uuid NULL,
    request_hash char(64) NULL,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_ledger_entries_id_ws_app_party UNIQUE (id, workspace_id, app_id, party_id),
    CONSTRAINT fk_ledger_entries_party FOREIGN KEY (party_id, workspace_id, app_id)
        REFERENCES parties(id, workspace_id, app_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_charge FOREIGN KEY (charge_id, workspace_id, app_id, party_id)
        REFERENCES ledger_entries(id, workspace_id, app_id, party_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_reversal FOREIGN KEY (reverses_entry_id, workspace_id, app_id, party_id)
        REFERENCES ledger_entries(id, workspace_id, app_id, party_id) ON DELETE RESTRICT,

    CONSTRAINT ledger_amount_range CHECK (amount_cents <> 0 AND abs(amount_cents) <= 100000000000),
    CONSTRAINT ledger_currency_usd CHECK (currency = 'USD'),
    CONSTRAINT ledger_label_len CHECK (label IS NULL OR (char_length(label) BETWEEN 1 AND 120)),
    CONSTRAINT ledger_note_len CHECK (note IS NULL OR (char_length(note) BETWEEN 1 AND 200)),
    CONSTRAINT ledger_entry_type_check CHECK (entry_type IN ('CHARGE', 'PAYMENT', 'REVERSAL')),

    CONSTRAINT ledger_type_rules CHECK (
        (entry_type = 'CHARGE' AND charge_id = id AND amount_cents > 0
            AND kind IS NOT NULL AND kind IN ('recurring', 'late_fee', 'manual')
            AND label IS NOT NULL AND due_date IS NOT NULL
            AND paid_on IS NULL AND reverses_entry_id IS NULL)
        OR
        (entry_type = 'PAYMENT' AND amount_cents < 0
            AND paid_on IS NOT NULL
            AND kind IS NULL AND schedule_id IS NULL AND period_key IS NULL
            AND label IS NULL AND due_date IS NULL AND reverses_entry_id IS NULL)
        OR
        (entry_type = 'REVERSAL'
            AND reverses_entry_id IS NOT NULL
            AND kind IS NULL AND schedule_id IS NULL AND period_key IS NULL
            AND label IS NULL AND due_date IS NULL AND paid_on IS NULL)
    )
);

CREATE UNIQUE INDEX idx_ledger_app_idempotency
    ON ledger_entries (app_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX idx_ledger_schedule_period
    ON ledger_entries (app_id, party_id, schedule_id, period_key)
    WHERE entry_type = 'CHARGE' AND period_key IS NOT NULL;

CREATE INDEX idx_ledger_party_created
    ON ledger_entries (app_id, party_id, created_at, id);

CREATE INDEX idx_ledger_charge_id
    ON ledger_entries (app_id, charge_id);

CREATE INDEX idx_ledger_workspace
    ON ledger_entries (workspace_id);

CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION prevent_ledger_modification();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_ledger_modification();
