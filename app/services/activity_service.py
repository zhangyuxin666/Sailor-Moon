import json
import uuid
from datetime import datetime

from ..agent.llm import LLMClient
from ..agent.orchestrator import Orchestrator
from ..core.exceptions import NotFoundError
from ..core.permissions import check_activity_owner
from ..scheduler.reminders import ReminderService
from ..tools.registry import configure_tools
from ..integrations.qq import QQBotService


class ActivityService:
    """业务门面：对外提供活动相关操作，统一做权限校验。"""

    def __init__(self, db, llm: LLMClient, reminders: ReminderService, queue=None):
        self.db = db
        self.llm = llm
        self.reminders = reminders
        self.orchestrator = Orchestrator(db, llm, reminders)
        self.queue = queue
        configure_tools(db)
        self.qq = QQBotService(db)

    def queue_activity(
        self, user_id: str, text: str, publish_to_qq: bool = True,
        class_id: str | None = None, workflow_config: dict | None = None,
    ) -> dict:
        """创建活动并提交持久化后台任务，供 HTTP API 使用。"""
        if self.queue is None:
            raise RuntimeError("未配置后台任务队列")
        activity_id = uuid.uuid4().hex[:12]
        with self.db.connect() as conn:
            conn.execute(
                "INSERT INTO activities (id, user_id, title, raw_input, status, created_at) "
                "VALUES (?, ?, ?, ?, 'queued', ?)",
                (activity_id, user_id, text[:20], text, datetime.now().isoformat()),
            )
            group_openid = None
            if class_id:
                classroom = conn.execute(
                    "SELECT c.qq_group_openid, co.organization_id FROM classes c "
                    "JOIN class_organizations co ON co.class_id = c.id WHERE c.id = ?",
                    (class_id,),
                ).fetchone()
                if not classroom:
                    raise NotFoundError("班级不存在")
                conn.execute(
                    "INSERT INTO activity_classes (activity_id, class_id, organization_id) VALUES (?, ?, ?)",
                    (activity_id, class_id, classroom["organization_id"]),
                )
                if workflow_config:
                    conn.execute(
                        "INSERT INTO activity_workflows (activity_id, config_json) VALUES (?, ?)",
                        (activity_id, json.dumps(workflow_config, ensure_ascii=False)),
                    )
                if publish_to_qq and classroom["qq_group_openid"]:
                    group_openid = classroom["qq_group_openid"]
                    conn.execute(
                        "INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at) VALUES (?, ?, ?, ?)",
                        (activity_id, user_id, group_openid, datetime.now().isoformat()),
                    )
            elif publish_to_qq:
                group_openid = self.qq.attach_activity(conn, user_id, activity_id)
        job = self.queue.enqueue("activity", activity_id)
        return {
            "activity_id": activity_id,
            "run_id": job["id"],
            "status": job["status"],
            "qq_group_attached": bool(group_openid),
        }

    def list_activities(self, user_id: str, class_ids: list[str] | None = None) -> list[dict]:
        with self.db.connect() as conn:
            if class_ids:
                items = []
                for class_id in class_ids:
                    rows = conn.execute(
                        "SELECT a.*, ac.class_id FROM activities a JOIN activity_classes ac ON ac.activity_id = a.id "
                        "WHERE ac.class_id = ? ORDER BY a.created_at DESC",
                        (class_id,),
                    ).fetchall()
                    items.extend(dict(row) for row in rows)
                return sorted(items, key=lambda item: item["created_at"], reverse=True)
            return [dict(row) for row in conn.execute(
                "SELECT * FROM activities WHERE user_id = ? ORDER BY created_at DESC",
                (user_id,),
            ).fetchall()]

    def run_queued_activity(self, activity_id: str) -> dict:
        """Worker 入口：读取已落库输入并执行活动工作流。"""
        with self.db.connect() as conn:
            row = conn.execute("SELECT * FROM activities WHERE id = ?", (activity_id,)).fetchone()
            if not row:
                raise NotFoundError(f"活动不存在: {activity_id}")
            conn.execute("UPDATE activities SET status = 'running' WHERE id = ?", (activity_id,))
        try:
            result = self.orchestrator.run(row["user_id"], activity_id, row["raw_input"])
            with self.db.connect() as conn:
                conn.execute("UPDATE activities SET status = 'ready' WHERE id = ?", (activity_id,))
            return result
        except Exception:
            with self.db.connect() as conn:
                conn.execute("UPDATE activities SET status = 'failed' WHERE id = ?", (activity_id,))
            raise

    def create_activity_from_text(self, user_id: str, text: str) -> dict:
        """一句话发起活动：建档 → 编排器自动跑全流程 → 返回活动详情。"""
        activity_id = uuid.uuid4().hex[:12]
        with self.db.connect() as conn:
            conn.execute(
                "INSERT INTO activities (id, user_id, title, raw_input, status, created_at) VALUES (?, ?, ?, ?, 'planned', ?)",
                (activity_id, user_id, text[:20], text, datetime.now().isoformat()),
            )
        self.orchestrator.run(user_id, activity_id, text)
        return self.get_activity(user_id, activity_id)

    def get_activity(self, user_id: str, activity_id: str) -> dict:
        with self.db.connect() as conn:
            check_activity_owner(conn, activity_id, user_id)
            activity = dict(
                conn.execute("SELECT * FROM activities WHERE id = ?", (activity_id,)).fetchone()
            )
            fetch = lambda sql: [dict(r) for r in conn.execute(sql, (activity_id,)).fetchall()]
            return {
                "activity": activity,
                "plan": json.loads(activity["plan_json"]) if activity["plan_json"] else None,
                "tasks": fetch("SELECT * FROM tasks WHERE activity_id = ?"),
                "forms": fetch("SELECT * FROM forms WHERE activity_id = ?"),
                "reminders": fetch("SELECT * FROM reminders WHERE activity_id = ?"),
                "steps": fetch("SELECT * FROM steps WHERE activity_id = ? ORDER BY id"),
                "deliveries": fetch("SELECT * FROM delivery_events WHERE activity_id = ? ORDER BY created_at DESC"),
            }

    def submit_registration(self, form_id: str, name: str, contact: str, extra: dict) -> dict:
        """提交报名。公开接口（报名人不是活动成员），不做活动权限校验。"""
        with self.db.connect() as conn:
            if not conn.execute("SELECT 1 FROM forms WHERE id = ?", (form_id,)).fetchone():
                raise NotFoundError(f"问卷不存在: {form_id}")
            cur = conn.execute(
                "INSERT INTO registrations (form_id, name, contact, extra_json, created_at) "
                "VALUES (?, ?, ?, ?, ?) RETURNING id",
                (form_id, name, contact, json.dumps(extra, ensure_ascii=False), datetime.now().isoformat()),
            )
            return {"registration_id": cur.fetchone()["id"], "form_id": form_id}

    def form_stats(self, user_id: str, activity_id: str) -> dict:
        with self.db.connect() as conn:
            check_activity_owner(conn, activity_id, user_id)
            form = conn.execute(
                "SELECT * FROM forms WHERE activity_id = ?", (activity_id,)
            ).fetchone()
            if not form:
                raise NotFoundError("该活动还没有报名问卷")
            rows = conn.execute(
                "SELECT name, contact, extra_json, created_at FROM registrations WHERE form_id = ? ORDER BY id",
                (form["id"],),
            ).fetchall()
            registrations = []
            for row in rows:
                item = dict(row)
                item["extra"] = json.loads(item.pop("extra_json") or "{}")
                registrations.append(item)
            return {
                "form_id": form["id"],
                "title": form["title"],
                "count": len(rows),
                "registrations": registrations,
            }

    def generate_recap(self, user_id: str, activity_id: str) -> dict:
        """活动后复盘：汇总报名数据 + 任务完成情况，由 LLM 生成总结。"""
        with self.db.connect() as conn:
            check_activity_owner(conn, activity_id, user_id)
            activity = conn.execute(
                "SELECT * FROM activities WHERE id = ?", (activity_id,)
            ).fetchone()
            stats = self.form_stats(user_id, activity_id) if conn.execute(
                "SELECT 1 FROM forms WHERE activity_id = ?", (activity_id,)
            ).fetchone() else {"count": 0, "registrations": []}
            tasks = conn.execute(
                "SELECT title, assignee, status FROM tasks WHERE activity_id = ?", (activity_id,)
            ).fetchall()
            task_summary = "；".join(
                f"{t['title']}({t['assignee']})-{t['status']}" for t in tasks
            ) or "无任务"
            recap = self.llm.generate_recap(activity["title"], stats, task_summary)
            conn.execute(
                "UPDATE activities SET status = 'finished' WHERE id = ?", (activity_id,)
            )
            return {"activity_id": activity_id, "recap": recap, "stats": stats}

    def cancel_reminder(self, user_id: str, reminder_id: str) -> dict:
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT activity_id FROM reminders WHERE id = ?", (reminder_id,)
            ).fetchone()
            if not row:
                raise NotFoundError(f"提醒不存在: {reminder_id}")
            check_activity_owner(conn, row["activity_id"], user_id)
            return self.reminders.cancel(conn, reminder_id)

    def update_task(self, user_id: str, task_id: str, status: str) -> dict:
        """更新任务进度，只有所属活动的创建者可以操作。"""
        with self.db.connect() as conn:
            row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
            if not row:
                raise NotFoundError(f"任务不存在: {task_id}")
            check_activity_owner(conn, row["activity_id"], user_id)
            conn.execute("UPDATE tasks SET status = ? WHERE id = ?", (status, task_id))
            return dict(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())

    def get_calendar_ics(self, user_id: str, activity_id: str) -> str:
        """读取已生成的日历文件，避免再次创建事件。"""
        with self.db.connect() as conn:
            check_activity_owner(conn, activity_id, user_id)
            row = conn.execute(
                "SELECT result_json FROM idempotency_keys WHERE key = ? AND tool_name = 'create_calendar_event'",
                (f"cal:{activity_id}",),
            ).fetchone()
            if not row:
                raise NotFoundError("该活动还没有日历事件")
            return json.loads(row["result_json"])["ics"]
