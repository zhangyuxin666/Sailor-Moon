-- 数据大屏实时推送：关键表变化时发出 PostgreSQL 通知。
-- 采用语句级触发器，一次批量写入只发一条通知，避免通知风暴。

CREATE OR REPLACE FUNCTION notify_analytics_change() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('analytics_changed', TG_TABLE_NAME);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_analytics_activities ON activities;
CREATE TRIGGER trg_analytics_activities
    AFTER INSERT OR UPDATE OR DELETE ON activities
    FOR EACH STATEMENT EXECUTE FUNCTION notify_analytics_change();

DROP TRIGGER IF EXISTS trg_analytics_tasks ON tasks;
CREATE TRIGGER trg_analytics_tasks
    AFTER INSERT OR UPDATE OR DELETE ON tasks
    FOR EACH STATEMENT EXECUTE FUNCTION notify_analytics_change();

DROP TRIGGER IF EXISTS trg_analytics_registrations ON registrations;
CREATE TRIGGER trg_analytics_registrations
    AFTER INSERT OR UPDATE OR DELETE ON registrations
    FOR EACH STATEMENT EXECUTE FUNCTION notify_analytics_change();

DROP TRIGGER IF EXISTS trg_analytics_check_ins ON activity_check_ins;
CREATE TRIGGER trg_analytics_check_ins
    AFTER INSERT OR UPDATE OR DELETE ON activity_check_ins
    FOR EACH STATEMENT EXECUTE FUNCTION notify_analytics_change();

DROP TRIGGER IF EXISTS trg_analytics_feedback ON activity_feedback;
CREATE TRIGGER trg_analytics_feedback
    AFTER INSERT OR UPDATE OR DELETE ON activity_feedback
    FOR EACH STATEMENT EXECUTE FUNCTION notify_analytics_change();
