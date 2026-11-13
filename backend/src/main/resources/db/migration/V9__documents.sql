CREATE TABLE document_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    party_id uuid NOT NULL,
    checklist_id text NOT NULL,
    item_id text NOT NULL,
    label text NOT NULL,
    required boolean NOT NULL DEFAULT false,
    expiry_days int NULL,
    due_date date NULL,
    status text NOT NULL,
    status_reason text NULL,
    current_document_id uuid NULL,
    approved_at timestamptz NULL,
    expires_on date NULL,
    version int NOT NULL DEFAULT 1,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cancelled_at timestamptz NULL,

    CONSTRAINT uq_document_requests_id_ws_app_party UNIQUE (id, workspace_id, app_id, party_id),
    CONSTRAINT fk_document_requests_party FOREIGN KEY (party_id, workspace_id, app_id)
        REFERENCES parties(id, workspace_id, app_id) ON DELETE RESTRICT,

    CONSTRAINT doc_requests_status CHECK (status IN ('PENDING', 'RECEIVED', 'APPROVED', 'REJECTED')),
    CONSTRAINT doc_requests_label_len CHECK (char_length(label) BETWEEN 1 AND 120),
    CONSTRAINT doc_requests_status_reason CHECK (
        (status = 'REJECTED' AND status_reason IS NOT NULL AND char_length(status_reason) BETWEEN 1 AND 200)
        OR
        (status <> 'REJECTED' AND status_reason IS NULL)
    ),
    CONSTRAINT doc_requests_expires_on CHECK (
        expires_on IS NULL OR status = 'APPROVED'
    ),
    CONSTRAINT doc_requests_version_pos CHECK (version >= 1)
);

CREATE UNIQUE INDEX idx_doc_requests_app_party_item
    ON document_requests (app_id, party_id, checklist_id, item_id)
    WHERE cancelled_at IS NULL;

CREATE UNIQUE INDEX idx_doc_requests_current_doc
    ON document_requests (current_document_id)
    WHERE current_document_id IS NOT NULL;

CREATE INDEX idx_doc_requests_app_party_status
    ON document_requests (app_id, party_id, status);

CREATE INDEX idx_doc_requests_app_due_date
    ON document_requests (app_id, due_date);

CREATE INDEX idx_doc_requests_workspace
    ON document_requests (workspace_id);

CREATE TABLE documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    party_id uuid NOT NULL,
    request_id uuid NOT NULL,
    version_no int NOT NULL,
    storage_key text NULL,
    original_filename text NOT NULL,
    content_type text NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    uploaded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    purged_at timestamptz NULL,

    CONSTRAINT uq_documents_id_ws_app_req UNIQUE (id, workspace_id, app_id, request_id),
    CONSTRAINT uq_documents_request_version UNIQUE (request_id, version_no),
    CONSTRAINT fk_documents_request FOREIGN KEY (request_id, workspace_id, app_id, party_id)
        REFERENCES document_requests(id, workspace_id, app_id, party_id) ON DELETE RESTRICT,

    CONSTRAINT documents_content_type CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    CONSTRAINT documents_size_bytes CHECK (size_bytes BETWEEN 1 AND 52428800),
    CONSTRAINT documents_version_no CHECK (version_no >= 1),
    CONSTRAINT documents_sha256_len CHECK (char_length(sha256) = 64),
    CONSTRAINT documents_storage_key_purge CHECK (
        (storage_key IS NULL AND purged_at IS NOT NULL)
        OR
        (storage_key IS NOT NULL AND purged_at IS NULL)
    )
);

CREATE INDEX idx_documents_app_party ON documents (app_id, party_id);
CREATE INDEX idx_documents_request ON documents (request_id);
CREATE INDEX idx_documents_workspace ON documents (workspace_id);

-- Foreign key from document_requests.current_document_id to documents
ALTER TABLE document_requests ADD CONSTRAINT fk_document_requests_current_doc
    FOREIGN KEY (current_document_id, workspace_id, app_id, id)
    REFERENCES documents(id, workspace_id, app_id, request_id) ON DELETE RESTRICT;

-- Triggers for documents immutability and purge
CREATE OR REPLACE FUNCTION check_documents_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.purged_at IS NULL
       AND NEW.purged_at IS NOT NULL
       AND OLD.storage_key IS NOT NULL
       AND NEW.storage_key IS NULL
       AND NEW.id = OLD.id
       AND NEW.workspace_id = OLD.workspace_id
       AND NEW.app_id = OLD.app_id
       AND NEW.party_id = OLD.party_id
       AND NEW.request_id = OLD.request_id
       AND NEW.version_no = OLD.version_no
       AND NEW.original_filename = OLD.original_filename
       AND NEW.content_type = OLD.content_type
       AND NEW.size_bytes = OLD.size_bytes
       AND NEW.sha256 = OLD.sha256
       AND NEW.uploaded_by = OLD.uploaded_by
       AND NEW.uploaded_at = OLD.uploaded_at THEN

        IF EXISTS (SELECT 1 FROM document_requests WHERE current_document_id = OLD.id) THEN
            RAISE EXCEPTION 'cannot purge current document of a request';
        END IF;

        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'documents is immutable: only setting purged_at and clearing storage_key is permitted';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_documents_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'documents cannot be deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_documents_update_purge_only
    BEFORE UPDATE ON documents
    FOR EACH ROW
    EXECUTE FUNCTION check_documents_update();

CREATE TRIGGER trg_documents_no_delete
    BEFORE DELETE ON documents
    FOR EACH ROW
    EXECUTE FUNCTION prevent_documents_delete();

CREATE TRIGGER trg_documents_no_truncate
    BEFORE TRUNCATE ON documents
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_documents_delete();
