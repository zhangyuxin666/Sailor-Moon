ALTER TABLE background_jobs ADD COLUMN worker_instance VARCHAR(120);
ALTER TABLE background_jobs ADD COLUMN lease_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE background_jobs ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE background_jobs ADD COLUMN deadline_at TIMESTAMPTZ;
CREATE INDEX ix_jobs_lease ON background_jobs(lease_until) WHERE status='running';
-- Pre-upgrade executions have no lease and are recovered at the next scan.
UPDATE background_jobs SET lease_until=clock_timestamp() WHERE status='running';
CREATE TABLE outbound_deliveries (
 logical_key VARCHAR(200) PRIMARY KEY, payload_hash VARCHAR(64) NOT NULL,
 group_openid VARCHAR(255) NOT NULL, content TEXT NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('sending','sent','failed','unknown')),
 external_id TEXT, error TEXT, attempts INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE delivery_events ADD COLUMN logical_key VARCHAR(200);
CREATE UNIQUE INDEX uq_delivery_logical ON delivery_events(logical_key) WHERE logical_key IS NOT NULL;
ALTER TABLE reminders ADD COLUMN error TEXT;
ALTER TABLE todo_reminders ADD COLUMN error TEXT;

ALTER TABLE classroom_deliveries ADD COLUMN logical_key VARCHAR(200);
CREATE UNIQUE INDEX uq_classroom_delivery_logical ON classroom_deliveries(logical_key) WHERE logical_key IS NOT NULL;
