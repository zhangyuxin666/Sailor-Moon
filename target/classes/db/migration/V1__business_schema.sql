CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS activities (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    title VARCHAR(255) NOT NULL,
    raw_input TEXT NOT NULL,
    plan_json TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_activities_user_id ON activities(user_id);

CREATE TABLE IF NOT EXISTS background_jobs (
    id VARCHAR(32) PRIMARY KEY,
    kind VARCHAR(40) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    created_at VARCHAR(40) NOT NULL,
    started_at VARCHAR(40),
    finished_at VARCHAR(40),
    CONSTRAINT uq_job_kind_ref UNIQUE(kind, ref_id)
);
CREATE INDEX IF NOT EXISTS ix_background_jobs_status ON background_jobs(status);

CREATE TABLE IF NOT EXISTS steps (
    id BIGSERIAL PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL,
    step VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    detail TEXT,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_steps_activity_id ON steps(activity_id);

CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(32) PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    assignee VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_tasks_activity_id ON tasks(activity_id);

CREATE TABLE IF NOT EXISTS forms (
    id VARCHAR(32) PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    fields_json TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_forms_activity_id ON forms(activity_id);

CREATE TABLE IF NOT EXISTS registrations (
    id BIGSERIAL PRIMARY KEY,
    form_id VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    contact VARCHAR(255) NOT NULL,
    extra_json TEXT,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_registrations_form_id ON registrations(form_id);

CREATE TABLE IF NOT EXISTS reminders (
    id VARCHAR(32) PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    remind_at VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uq_reminder_activity_time UNIQUE(activity_id, remind_at)
);
CREATE INDEX IF NOT EXISTS ix_reminders_status_time ON reminders(status, remind_at);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    activity_id VARCHAR(32),
    recipient VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS qq_bindings (
    group_openid VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    group_label VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    last_seen_at VARCHAR(40) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_qq_bindings_user_id ON qq_bindings(user_id);

CREATE TABLE IF NOT EXISTS qq_binding_codes (
    code VARCHAR(12) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    expires_at VARCHAR(40) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS activity_channels (
    activity_id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    group_openid VARCHAR(255) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS activity_classes (
    activity_id VARCHAR(32) PRIMARY KEY,
    class_id VARCHAR(32) NOT NULL,
    organization_id VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS activity_task_assignees (
    task_id VARCHAR(32) PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS activity_workflows (
    activity_id VARCHAR(32) PRIMARY KEY,
    config_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_drafts (
    id VARCHAR(32) PRIMARY KEY,
    organization_id VARCHAR(32) NOT NULL,
    class_id VARCHAR(32) NOT NULL,
    creator_id VARCHAR(32) NOT NULL,
    raw_input TEXT NOT NULL,
    intent_type VARCHAR(32) NOT NULL,
    complexity VARCHAR(32) NOT NULL,
    draft_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    result_type VARCHAR(32),
    result_id VARCHAR(64),
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS delivery_events (
    id VARCHAR(32) PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    target VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    external_id VARCHAR(255),
    error TEXT,
    created_at VARCHAR(40) NOT NULL,
    completed_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS qq_inbound_events (
    message_id VARCHAR(255) PRIMARY KEY,
    group_openid VARCHAR(255) NOT NULL,
    sender_openid VARCHAR(255),
    sender_name VARCHAR(255),
    content TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS integration_state (
    key VARCHAR(80) PRIMARY KEY,
    value_json TEXT NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    student_no VARCHAR(80),
    password_hash TEXT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL,
    expires_at VARCHAR(40) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_auth_sessions_account_id ON auth_sessions(account_id);

CREATE TABLE IF NOT EXISTS classes (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    manager_id VARCHAR(32) NOT NULL,
    qq_group_openid VARCHAR(255),
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS class_members (
    class_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(class_id, account_id)
);

CREATE TABLE IF NOT EXISTS todos (
    id VARCHAR(32) PRIMARY KEY,
    class_id VARCHAR(32) NOT NULL,
    creator_id VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    kind VARCHAR(32) NOT NULL DEFAULT 'homework',
    deadline VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'open',
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS todo_assignees (
    todo_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    note TEXT,
    file_path TEXT,
    original_filename VARCHAR(255),
    submitted_at VARCHAR(40),
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(todo_id, account_id)
);

CREATE TABLE IF NOT EXISTS todo_reminders (
    id VARCHAR(32) PRIMARY KEY,
    todo_id VARCHAR(32) NOT NULL,
    remind_at VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uq_todo_reminder UNIQUE(todo_id, remind_at)
);

CREATE TABLE IF NOT EXISTS classroom_deliveries (
    id VARCHAR(32) PRIMARY KEY,
    todo_id VARCHAR(32) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    external_id VARCHAR(255),
    error TEXT,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS organizations (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    owner_account_id VARCHAR(32) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS organization_members (
    organization_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(organization_id, account_id)
);

CREATE TABLE IF NOT EXISTS class_organizations (
    class_id VARCHAR(32) PRIMARY KEY,
    organization_id VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS account_security (
    account_id VARCHAR(32) PRIMARY KEY,
    force_password_change INTEGER NOT NULL DEFAULT 0,
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until VARCHAR(40),
    password_changed_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_consents (
    account_id VARCHAR(32) PRIMARY KEY,
    policy_version VARCHAR(20) NOT NULL,
    accepted_at VARCHAR(40) NOT NULL,
    ip_address VARCHAR(80)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id VARCHAR(32) PRIMARY KEY,
    organization_id VARCHAR(32),
    account_id VARCHAR(32),
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80),
    resource_id VARCHAR(64),
    detail_json TEXT,
    ip_address VARCHAR(80),
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_audit_org_created ON audit_logs(organization_id, created_at);

CREATE TABLE IF NOT EXISTS file_assets (
    id VARCHAR(32) PRIMARY KEY,
    todo_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    storage_key TEXT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(160),
    size_bytes BIGINT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(255) PRIMARY KEY,
    tool_name VARCHAR(100) NOT NULL,
    result_json TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS rag_documents (
    id UUID PRIMARY KEY,
    organization_id VARCHAR(32),
    source VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1536) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_rag_documents_organization ON rag_documents(organization_id);
CREATE INDEX IF NOT EXISTS ix_rag_documents_embedding
    ON rag_documents USING hnsw (embedding vector_cosine_ops);
