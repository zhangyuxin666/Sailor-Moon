import logging
import time
import uuid
from datetime import datetime

from .config import settings

logger = logging.getLogger(__name__)


class JobQueue:
    """数据库持久化任务队列。单条任务有稳定 ref_id，可安全重复提交。"""

    def __init__(self, db):
        self.db = db

    def enqueue(self, kind: str, ref_id: str) -> dict:
        with self.db.connect() as conn:
            old = conn.execute(
                "SELECT * FROM background_jobs WHERE kind = ? AND ref_id = ?", (kind, ref_id)
            ).fetchone()
            if old:
                return dict(old)
            job_id = uuid.uuid4().hex[:12]
            now = datetime.now().isoformat()
            conn.execute(
                "INSERT INTO background_jobs (id, kind, ref_id, status, attempts, created_at) "
                "VALUES (?, ?, ?, 'queued', 0, ?)",
                (job_id, kind, ref_id, now),
            )
            return {"id": job_id, "kind": kind, "ref_id": ref_id, "status": "queued", "attempts": 0}

    def get(self, job_id: str):
        with self.db.connect() as conn:
            row = conn.execute("SELECT * FROM background_jobs WHERE id = ?", (job_id,)).fetchone()
            return dict(row) if row else None

    def claim_next(self):
        """原子抢占下一条任务；PostgreSQL 可横向启动多个 Worker。"""
        now = datetime.now().isoformat()
        with self.db.connect() as conn:
            if self.db.url.startswith("postgresql"):
                row = conn.execute(
                    "SELECT * FROM background_jobs WHERE status = 'queued' ORDER BY created_at "
                    "FOR UPDATE SKIP LOCKED LIMIT 1"
                ).fetchone()
            else:
                row = conn.execute(
                    "SELECT * FROM background_jobs WHERE status = 'queued' ORDER BY created_at LIMIT 1"
                ).fetchone()
            if not row:
                return None
            conn.execute(
                "UPDATE background_jobs SET status = 'running', attempts = attempts + 1, started_at = ? "
                "WHERE id = ? AND status = 'queued'",
                (now, row["id"]),
            )
            claimed = dict(row)
            claimed["attempts"] += 1
            return claimed

    def finish(self, job_id: str):
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE background_jobs SET status = 'succeeded', finished_at = ?, error = NULL WHERE id = ?",
                (datetime.now().isoformat(), job_id),
            )

    def fail(self, job: dict, error: Exception):
        status = "failed" if job["attempts"] >= settings.worker_max_attempts else "queued"
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE background_jobs SET status = ?, error = ?, finished_at = ? WHERE id = ?",
                (status, str(error)[:2000], datetime.now().isoformat(), job["id"]),
            )


class Worker:
    def __init__(self, queue, service, reminders, classroom_service=None):
        self.queue = queue
        self.service = service
        self.reminders = reminders
        self.classroom_service = classroom_service

    def run_once(self) -> bool:
        job = self.queue.claim_next()
        if not job:
            return False
        try:
            if job["kind"] == "activity":
                self.service.run_queued_activity(job["ref_id"])
            elif job["kind"] == "reminder":
                self.reminders.deliver(job["ref_id"])
            elif job["kind"] == "todo_reminder":
                with self.queue.db.connect() as conn:
                    reminder = conn.execute(
                        "SELECT * FROM todo_reminders WHERE id = ? AND status = 'scheduled'",
                        (job["ref_id"],),
                    ).fetchone()
                if reminder and self.classroom_service:
                    self.classroom_service.remind_missing(None, reminder["todo_id"], scheduled=True)
                    with self.queue.db.connect() as conn:
                        conn.execute(
                            "UPDATE todo_reminders SET status = 'sent' WHERE id = ?", (job["ref_id"],)
                        )
            else:
                raise ValueError(f"未知任务类型: {job['kind']}")
            self.queue.finish(job["id"])
        except Exception as exc:
            logger.exception("后台任务失败: %s", job["id"])
            self.queue.fail(job, exc)
        return True

    def run_forever(self):
        logger.info("后台 Worker 已启动")
        while True:
            if not self.run_once():
                time.sleep(settings.worker_poll_seconds)
