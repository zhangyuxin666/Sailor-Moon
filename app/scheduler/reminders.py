import logging
import uuid
from datetime import datetime

from apscheduler.schedulers.background import BackgroundScheduler

from ..core.exceptions import NotFoundError
from ..tools.base import ToolContext
from ..tools.registry import get_tool

logger = logging.getLogger(__name__)


class ReminderService:
    """定时提醒：调度、取消、服务重启后从 DB 重建未触发的提醒。

    到期触发时通过 send_message 工具发送，幂等键为 reminder:{id}，
    即使调度器异常重试也不会重复发送。
    """

    def __init__(self, db, scheduler=None):
        self.db = db
        self.scheduler = scheduler or BackgroundScheduler()

    def start(self):
        if not self.scheduler.running:
            self.scheduler.start()
        self.restore_pending()

    def shutdown(self):
        if self.scheduler.running:
            self.scheduler.shutdown(wait=False)

    def schedule(self, conn, activity_id: str, remind_at: datetime, message: str) -> dict:
        """调度提醒。同一活动同一时间的 scheduled 提醒已存在则复用（幂等）。"""
        row = conn.execute(
            "SELECT * FROM reminders WHERE activity_id = ? AND remind_at = ? AND status = 'scheduled'",
            (activity_id, remind_at.isoformat()),
        ).fetchone()
        if row:
            return dict(row)
        reminder_id = uuid.uuid4().hex[:12]
        conn.execute(
            "INSERT INTO reminders (id, activity_id, message, remind_at, status, created_at) VALUES (?, ?, ?, ?, 'scheduled', ?)",
            (reminder_id, activity_id, message, remind_at.isoformat(), datetime.now().isoformat()),
        )
        if remind_at > datetime.now():
            self._add_job(reminder_id, remind_at)
        return {
            "id": reminder_id,
            "activity_id": activity_id,
            "message": message,
            "remind_at": remind_at.isoformat(),
            "status": "scheduled",
        }

    def cancel(self, conn, reminder_id: str) -> dict:
        """取消提醒：从调度器移除 + DB 状态置为 cancelled。"""
        row = conn.execute(
            "SELECT * FROM reminders WHERE id = ?", (reminder_id,)
        ).fetchone()
        if not row:
            raise NotFoundError(f"提醒不存在: {reminder_id}")
        conn.execute(
            "UPDATE reminders SET status = 'cancelled' WHERE id = ?", (reminder_id,)
        )
        try:
            self.scheduler.remove_job(reminder_id)
        except Exception:
            pass  # 任务可能已触发或调度器未启动，DB 状态为准
        return {"id": reminder_id, "status": "cancelled"}

    def restore_pending(self):
        """启动时重建：未触发且未取消的提醒重新挂到调度器，已过期的立即补发。"""
        with self.db.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM reminders WHERE status = 'scheduled'"
            ).fetchall()
        for row in rows:
            remind_at = datetime.fromisoformat(row["remind_at"])
            if remind_at > datetime.now():
                self._add_job(row["id"], remind_at)
            else:
                self._fire(row["id"])

    def _add_job(self, reminder_id: str, remind_at: datetime):
        self.scheduler.add_job(
            self._fire, "date", run_date=remind_at, args=[reminder_id],
            id=reminder_id, replace_existing=True,
        )

    def _fire(self, reminder_id: str):
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM reminders WHERE id = ? AND status = 'scheduled'", (reminder_id,)
            ).fetchone()
            if not row:
                return  # 已取消或已发送
            ctx = ToolContext(conn=conn, activity_id=row["activity_id"])
            get_tool("send_message").execute(
                ctx,
                idempotency_key=f"reminder:{reminder_id}",
                recipient="all",
                content=row["message"],
            )
            conn.execute(
                "UPDATE reminders SET status = 'sent' WHERE id = ?", (reminder_id,)
            )
            logger.info("提醒已发送: %s", reminder_id)
