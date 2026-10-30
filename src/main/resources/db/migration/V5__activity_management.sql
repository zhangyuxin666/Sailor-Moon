ALTER TABLE forms ADD COLUMN capacity INTEGER CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 100000);
ALTER TABLE forms ADD COLUMN registration_deadline VARCHAR(40);
ALTER TABLE forms ADD COLUMN registration_status VARCHAR(16) NOT NULL DEFAULT 'open' CHECK (registration_status IN ('open','closed'));
ALTER TABLE forms ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE registrations ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','cancelled'));
ALTER TABLE registrations ADD COLUMN identity_key VARCHAR(64);
ALTER TABLE registrations ADD COLUMN request_id VARCHAR(80);
ALTER TABLE registrations ADD COLUMN cancel_token_hash VARCHAR(64);
ALTER TABLE registrations ADD COLUMN cancelled_at VARCHAR(40);
WITH identities AS (
 SELECT id, encode(sha256(convert_to(lower(trim(normalize(name,NFKC))) || E'\n' ||
 lower(regexp_replace(normalize(contact,NFKC),'\s','','g')),'UTF8')),'hex') AS identity,
 row_number() OVER (PARTITION BY form_id,lower(trim(normalize(name,NFKC))),lower(regexp_replace(normalize(contact,NFKC),'\s','','g')) ORDER BY id) AS ordinal
 FROM registrations
) UPDATE registrations r SET identity_key=i.identity FROM identities i WHERE r.id=i.id AND i.ordinal=1;
CREATE UNIQUE INDEX uq_active_registration_identity ON registrations(form_id,identity_key) WHERE status='active' AND identity_key IS NOT NULL;
CREATE UNIQUE INDEX uq_registration_request ON registrations(form_id,request_id) WHERE request_id IS NOT NULL;
ALTER TABLE activities ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE activities ADD COLUMN cancelled_at VARCHAR(40);
ALTER TABLE activities ADD COLUMN cancel_reason TEXT;
ALTER TABLE tasks ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE tasks ADD COLUMN deadline VARCHAR(40);
ALTER TABLE tasks ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'normal' CHECK (priority IN ('low','normal','high','urgent'));
ALTER TABLE tasks ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE tasks ADD COLUMN plan_key VARCHAR(80);
ALTER TABLE tasks ADD COLUMN unassigned_reason TEXT;
UPDATE tasks SET plan_key='legacy:'||id;
CREATE UNIQUE INDEX uq_activity_plan_task ON tasks(activity_id,plan_key) WHERE plan_key IS NOT NULL;
CREATE TABLE task_dependencies (
 task_id VARCHAR(32) REFERENCES tasks(id) ON DELETE CASCADE,
 depends_on_id VARCHAR(32) REFERENCES tasks(id) ON DELETE CASCADE,
 PRIMARY KEY(task_id,depends_on_id), CHECK(task_id<>depends_on_id)
);
ALTER TABLE messages ADD COLUMN recipient_account_id VARCHAR(32);
CREATE INDEX ix_messages_account ON messages(recipient_account_id,id);
CREATE TABLE activity_changes (
 id VARCHAR(32) PRIMARY KEY, activity_id VARCHAR(32) NOT NULL, request_id VARCHAR(80) NOT NULL,
 kind VARCHAR(40) NOT NULL, actor_id VARCHAR(32) NOT NULL, payload_json TEXT NOT NULL,
 result_json TEXT NOT NULL, content TEXT NOT NULL, notification_status VARCHAR(20) NOT NULL DEFAULT 'pending',
 error TEXT, created_at VARCHAR(40) NOT NULL, completed_at VARCHAR(40),
 UNIQUE(activity_id,request_id)
);
