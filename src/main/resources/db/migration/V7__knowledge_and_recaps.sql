ALTER TABLE rag_documents ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE rag_documents ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();
-- Historical vectors have no trustworthy embedding profile. Preserve them for explicit reindexing.
ALTER TABLE rag_documents ADD COLUMN embedding_profile VARCHAR(160) NOT NULL DEFAULT 'legacy_unknown';
CREATE TABLE activity_recaps (
 id VARCHAR(32) PRIMARY KEY,activity_id VARCHAR(32) NOT NULL,version INTEGER NOT NULL,
 request_id VARCHAR(80) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'generating',
 snapshot_json TEXT NOT NULL,body TEXT,generation_json TEXT,error TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),completed_at TIMESTAMPTZ,
 UNIQUE(activity_id,version),UNIQUE(activity_id,request_id)
);
