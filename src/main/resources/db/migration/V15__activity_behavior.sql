CREATE TABLE behavior_events (
    account_id varchar(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    event_id uuid NOT NULL,
    activity_id varchar(32) NOT NULL,
    event_type varchar(20) NOT NULL,
    category varchar(20) NOT NULL,
    duration_ms integer NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, event_id)
);
CREATE TABLE user_interest_features (
    account_id varchar(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category varchar(20) NOT NULL,
    views bigint NOT NULL,
    dwell_ms bigint NOT NULL,
    share_clicks bigint NOT NULL,
    score bigint NOT NULL,
    PRIMARY KEY (account_id, category)
);
CREATE TABLE daily_activity_recommendations (
    account_id varchar(32) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    activity_id varchar(32) NOT NULL,
    recommendation_date date NOT NULL,
    score bigint NOT NULL,
    PRIMARY KEY (account_id, activity_id, recommendation_date)
);
