-- 文件中心：分片上传会话与分片记录
-- 支持大文件分片上传、断点续传、分片校验与秒传

CREATE TABLE IF NOT EXISTS upload_sessions (
    id                VARCHAR(32) PRIMARY KEY,
    account_id        VARCHAR(32) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(160),
    size_bytes        BIGINT NOT NULL,
    chunk_size        INT NOT NULL,
    total_chunks      INT NOT NULL,
    file_sha256       VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'uploading',
    created_at        VARCHAR(40) NOT NULL,
    updated_at        VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_upload_sessions_account ON upload_sessions(account_id, status);

CREATE TABLE IF NOT EXISTS upload_chunks (
    session_id  VARCHAR(32) NOT NULL REFERENCES upload_sessions(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    size_bytes  BIGINT NOT NULL,
    sha256      VARCHAR(64) NOT NULL,
    created_at  VARCHAR(40) NOT NULL,
    PRIMARY KEY (session_id, chunk_index)
);

ALTER TABLE file_assets ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);
ALTER TABLE file_assets ADD COLUMN IF NOT EXISTS upload_id VARCHAR(32);
ALTER TABLE file_assets ADD COLUMN IF NOT EXISTS upload_mode VARCHAR(16) DEFAULT 'simple';

-- 文件中心里的文件不属于某个待办
ALTER TABLE file_assets ALTER COLUMN todo_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS ix_file_assets_account ON file_assets(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_file_assets_sha ON file_assets(account_id, sha256);
