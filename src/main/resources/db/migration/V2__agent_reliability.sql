ALTER TABLE background_jobs ADD COLUMN IF NOT EXISTS next_attempt_at VARCHAR(40);
ALTER TABLE background_jobs ADD COLUMN IF NOT EXISTS locked_by VARCHAR(80);
ALTER TABLE background_jobs ADD COLUMN IF NOT EXISTS heartbeat_at VARCHAR(40);
ALTER TABLE background_jobs ADD COLUMN IF NOT EXISTS updated_at VARCHAR(40);

UPDATE background_jobs SET updated_at = COALESCE(finished_at, started_at, created_at)
WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_background_jobs_claim
    ON background_jobs(status, next_attempt_at, created_at);

ALTER TABLE steps ADD COLUMN IF NOT EXISTS phase VARCHAR(32) NOT NULL DEFAULT 'execution';
ALTER TABLE steps ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);
ALTER TABLE steps ADD COLUMN IF NOT EXISTS input_json TEXT;
ALTER TABLE steps ADD COLUMN IF NOT EXISTS output_json TEXT;
ALTER TABLE steps ADD COLUMN IF NOT EXISTS error TEXT;
ALTER TABLE steps ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS ix_steps_activity_phase ON steps(activity_id, phase, id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_steps_activity_idempotency
    ON steps(activity_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_runs (
    id VARCHAR(32) PRIMARY KEY,
    kind VARCHAR(40) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(32),
    actor_id VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    phase VARCHAR(32) NOT NULL DEFAULT 'planning',
    plan_json TEXT,
    result_json TEXT,
    error TEXT,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT uq_agent_run_kind_ref UNIQUE(kind, ref_id)
);
CREATE INDEX IF NOT EXISTS ix_agent_runs_org_created ON agent_runs(organization_id, created_at);

ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS created_by VARCHAR(32);
CREATE UNIQUE INDEX IF NOT EXISTS ux_rag_documents_scope_hash
    ON rag_documents(COALESCE(organization_id, ''), content_hash)
    WHERE content_hash IS NOT NULL;
