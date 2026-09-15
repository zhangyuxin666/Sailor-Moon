from datetime import datetime, timedelta

import pytest

from app.core.exceptions import NotFoundError


def test_schedule_and_cancel_reminder(db, reminders):
    """提醒可调度、可取消：取消后 DB 状态为 cancelled，调度器中任务被移除。"""
    with db.connect() as conn:
        r = reminders.schedule(conn, "act1", datetime.now() + timedelta(hours=1), "别忘了活动")
        assert r["status"] == "scheduled"
        assert reminders.scheduler.get_job(r["id"]) is not None

        result = reminders.cancel(conn, r["id"])
        assert result["status"] == "cancelled"
        assert reminders.scheduler.get_job(r["id"]) is None
        row = conn.execute("SELECT status FROM reminders WHERE id = ?", (r["id"],)).fetchone()
        assert row["status"] == "cancelled"


def test_schedule_is_idempotent(db, reminders):
    """同一活动同一时间重复调度，复用已有提醒。"""
    remind_at = datetime.now() + timedelta(hours=2)
    with db.connect() as conn:
        r1 = reminders.schedule(conn, "act1", remind_at, "msg")
        r2 = reminders.schedule(conn, "act1", remind_at, "msg")
        assert r1["id"] == r2["id"]
        count = conn.execute(
            "SELECT COUNT(*) AS c FROM reminders WHERE activity_id = 'act1'"
        ).fetchone()["c"]
        assert count == 1


def test_cancel_missing_reminder(db, reminders):
    with db.connect() as conn:
        with pytest.raises(NotFoundError):
            reminders.cancel(conn, "no-such-id")


def test_restore_pending_rebuilds_jobs(db, reminders):
    """重启后从 DB 重建未触发的提醒。"""
    with db.connect() as conn:
        r = reminders.schedule(conn, "act1", datetime.now() + timedelta(hours=1), "msg")
    reminders.scheduler.remove_all_jobs()
    assert reminders.scheduler.get_job(r["id"]) is None
    reminders.restore_pending()
    assert reminders.scheduler.get_job(r["id"]) is not None
