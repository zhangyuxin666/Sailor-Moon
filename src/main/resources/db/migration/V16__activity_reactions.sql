CREATE TABLE activity_reactions (
    account_id varchar(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    activity_id varchar(32) NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    kind varchar(10) NOT NULL CHECK (kind IN ('like', 'favorite')),
    category varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, activity_id, kind)
);

CREATE INDEX ix_activity_reactions_activity_kind ON activity_reactions(activity_id, kind);
