CREATE TABLE participation_settings(
 activity_id VARCHAR(32) PRIMARY KEY REFERENCES activities(id) ON DELETE CASCADE,
 attendance_open BOOLEAN NOT NULL DEFAULT false,feedback_open BOOLEAN NOT NULL DEFAULT false,
 version INTEGER NOT NULL DEFAULT 1,roster_confirmed BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE activity_participants(
 activity_id VARCHAR(32) REFERENCES activities(id) ON DELETE CASCADE,account_id VARCHAR(32) NOT NULL,added_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(activity_id,account_id)
);
CREATE TABLE activity_check_ins(
 activity_id VARCHAR(32) NOT NULL,account_id VARCHAR(32) NOT NULL,checked_by VARCHAR(32) NOT NULL,checked_in_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(activity_id,account_id),FOREIGN KEY(activity_id,account_id) REFERENCES activity_participants(activity_id,account_id) ON DELETE CASCADE
);
CREATE TABLE activity_feedback(
 activity_id VARCHAR(32) NOT NULL,account_id VARCHAR(32) NOT NULL,rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),comment VARCHAR(2000) NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 1,created_at VARCHAR(40) NOT NULL,updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(activity_id,account_id),FOREIGN KEY(activity_id,account_id) REFERENCES activity_participants(activity_id,account_id) ON DELETE CASCADE
);
CREATE TABLE participation_requests(
 activity_id VARCHAR(32) REFERENCES activities(id) ON DELETE CASCADE,actor_id VARCHAR(32) NOT NULL,request_id VARCHAR(80) NOT NULL,
 kind VARCHAR(40) NOT NULL,payload_json TEXT NOT NULL,result_json TEXT NOT NULL,created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(activity_id,actor_id,request_id)
);

CREATE TABLE business_templates (
 id VARCHAR(32) PRIMARY KEY,
 organization_id VARCHAR(32) NOT NULL,
 creator_id VARCHAR(32) NOT NULL,
 name VARCHAR(120) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 blueprint_json TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_templates_org ON business_templates(organization_id,updated_at);
CREATE TABLE template_applications (
 account_id VARCHAR(32) NOT NULL,
 request_id VARCHAR(80) NOT NULL,
 template_id VARCHAR(32) NOT NULL,
 class_id VARCHAR(32) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 draft_id VARCHAR(32) NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(account_id,request_id)
);
