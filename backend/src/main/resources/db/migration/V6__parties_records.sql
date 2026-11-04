CREATE TABLE parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    party_type text NOT NULL,
    display_name text NOT NULL,
    data jsonb NOT NULL,
    definition_version int NOT NULL,
    version int NOT NULL DEFAULT 1,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT parties_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT parties_version_pos CHECK (version >= 1),
    CONSTRAINT parties_data_obj CHECK (jsonb_typeof(data) = 'object'),
    CONSTRAINT parties_data_size CHECK (octet_length(data::text) <= 16384)
);
CREATE INDEX idx_parties_app_type_name ON parties (app_id, party_type, lower(display_name));
CREATE INDEX idx_parties_workspace ON parties (workspace_id);

CREATE TABLE records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    app_id uuid NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    record_type text NOT NULL,
    display_name text NOT NULL,
    data jsonb NOT NULL,
    definition_version int NOT NULL,
    version int NOT NULL DEFAULT 1,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT records_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT records_version_pos CHECK (version >= 1),
    CONSTRAINT records_data_obj CHECK (jsonb_typeof(data) = 'object'),
    CONSTRAINT records_data_size CHECK (octet_length(data::text) <= 16384)
);
CREATE INDEX idx_records_app_type_name ON records (app_id, record_type, lower(display_name));
CREATE INDEX idx_records_workspace ON records (workspace_id);
