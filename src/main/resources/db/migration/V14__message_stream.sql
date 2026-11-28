-- 站内消息实时推送：新消息插入时通知在线用户。
-- 行级触发器，因为每条消息的收件人不同；payload 只带 id 和收件人，正文仍从库里取。

CREATE OR REPLACE FUNCTION notify_user_message() RETURNS trigger AS $$
BEGIN
    IF NEW.recipient_account_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify('user_messages', json_build_object(
        'id', NEW.id,
        'to', NEW.recipient_account_id
    )::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_user_messages ON messages;
CREATE TRIGGER trg_user_messages
    AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION notify_user_message();
