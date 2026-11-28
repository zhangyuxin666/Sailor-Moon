-- Flink SQL demo. Start a Kafka broker and create the activity-behavior-demo topic.
-- Run this file with a Kafka SQL connector compatible with the installed Flink version.
CREATE TABLE activity_behavior_demo (
    account_id STRING,
    category STRING,
    event_type STRING,
    duration_ms INT,
    event_time_ms BIGINT,
    event_time AS TO_TIMESTAMP_LTZ(event_time_ms, 3),
    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'activity-behavior-demo',
    'properties.bootstrap.servers' = 'localhost:9092',
    'properties.group.id' = 'activity-interest-demo',
    'scan.startup.mode' = 'earliest-offset',
    'format' = 'json'
);

CREATE TABLE interest_window_output (
    account_id STRING,
    category STRING,
    window_start TIMESTAMP(3),
    window_end TIMESTAMP(3),
    views BIGINT,
    dwell_ms BIGINT,
    share_clicks BIGINT,
    score BIGINT
) WITH ('connector' = 'print');

INSERT INTO interest_window_output
SELECT account_id, category, window_start, window_end,
       SUM(CAST(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END AS BIGINT)),
       SUM(CAST(CASE WHEN event_type = 'dwell' THEN duration_ms ELSE 0 END AS BIGINT)),
       SUM(CAST(CASE WHEN event_type = 'share_clicked' THEN 1 ELSE 0 END AS BIGINT)),
       SUM(CAST(CASE WHEN event_type = 'view' THEN 1
                     WHEN event_type = 'dwell' AND duration_ms >= 30000 THEN 2
                     WHEN event_type = 'share_clicked' THEN 3 ELSE 0 END AS BIGINT))
FROM TABLE(TUMBLE(TABLE activity_behavior_demo, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE event_type IN ('view', 'dwell', 'share_clicked')
GROUP BY account_id, category, window_start, window_end;
