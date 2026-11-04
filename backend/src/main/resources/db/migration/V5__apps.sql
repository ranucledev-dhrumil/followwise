CREATE TABLE apps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    template_key text,
    current_version int NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);
CREATE INDEX idx_apps_workspace_id ON apps(workspace_id);

CREATE TABLE app_definition_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id uuid NOT NULL REFERENCES apps(id),
    version int NOT NULL CHECK (version >= 1),
    definition jsonb NOT NULL,
    note text,
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_id, version)
);
CREATE INDEX idx_app_definition_versions_app_id ON app_definition_versions(app_id);
