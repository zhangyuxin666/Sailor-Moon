CREATE TABLE account_login_methods (
    account_id VARCHAR(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider VARCHAR(10) NOT NULL CHECK (provider IN ('phone', 'wechat')),
    subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (account_id, provider),
    UNIQUE (provider, subject)
);

CREATE TABLE phone_login_challenges (
    id VARCHAR(32) PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    phone VARCHAR(11) NOT NULL,
    binding_session_hash VARCHAR(128),
    code_hash VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_phone_login_challenges_expiry ON phone_login_challenges(expires_at);

CREATE TABLE wechat_login_states (
    state VARCHAR(32) PRIMARY KEY,
    browser_hash VARCHAR(64) NOT NULL,
    binding_account_id VARCHAR(32) REFERENCES accounts(id) ON DELETE CASCADE,
    binding_session_hash VARCHAR(128),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_wechat_login_states_expiry ON wechat_login_states(expires_at);

CREATE TABLE login_method_limits (
    bucket VARCHAR(160) PRIMARY KEY,
    requests INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_login_method_limits_expiry ON login_method_limits(expires_at);
