CREATE TABLE class_chat_messages (
    class_id VARCHAR(32) NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    sender_id VARCHAR(32) NOT NULL REFERENCES accounts(id),
    client_message_id VARCHAR(64) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (class_id, sequence),
    UNIQUE (class_id, sender_id, client_message_id)
);
