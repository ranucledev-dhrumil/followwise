-- V11: Indexes for forms and dashboard generation
-- Note: ledger_entries (app_id, charge_id) already exists from V7 as idx_ledger_charge_id.

CREATE INDEX IF NOT EXISTS idx_ledger_app_due_date_charge
    ON ledger_entries (app_id, due_date)
    WHERE entry_type = 'CHARGE';

CREATE INDEX IF NOT EXISTS idx_ledger_app_paid_on_payment
    ON ledger_entries (app_id, paid_on)
    WHERE entry_type = 'PAYMENT';

CREATE INDEX IF NOT EXISTS idx_doc_requests_app_status_due_date
    ON document_requests (app_id, status, due_date);

CREATE INDEX IF NOT EXISTS idx_doc_requests_app_party
    ON document_requests (app_id, party_id);

CREATE INDEX IF NOT EXISTS idx_parties_app_type_unarchived
    ON parties (app_id, party_type)
    WHERE archived_at IS NULL;
