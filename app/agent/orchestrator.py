import logging
import uuid
from datetime import datetime, timedelta

from ..tools.base import ToolContext
from ..tools.message_tool import make_message_key
from ..tools.registry import get_tool
from . import planner
from .llm import LLMClient

logger = logging.getLogger(__name__)


class Orchestrator:
    """Agent 编排器：规划 → 执行（逐步调工具，每步落库）→ 交付结果汇总。

    所有副作用操作都带幂等键，重跑不会产生重复消息/表单/任务。
    """

    def __init__(self, db, llm: LLMClient, reminder_service):
        self.db = db
        self.llm = llm
        self.reminders = reminder_service

    def _log_step(self, conn, activity_id: str, step: str, status: str, detail: str = ""):
        conn.execute(
            "INSERT INTO steps (activity_id, step, status, detail, created_at) VALUES (?, ?, ?, ?, ?)",
            (activity_id, step, status, detail, datetime.now().isoformat()),
        )

    def run(self, user_id: str, activity_id: str, raw_input: str) -> dict:
        with self.db.connect() as conn:
            ctx = ToolContext(conn=conn, user_id=user_id, activity_id=activity_id)

            # 1. 规划：一句话 → 结构化策划
            plan = planner.plan_activity(self.llm, raw_input)
            conn.execute(
                "UPDATE activities SET title = ?, plan_json = ? WHERE id = ?",
                (plan.title, plan.model_dump_json(), activity_id),
            )
            self._log_step(conn, activity_id, "generate_plan", "done", plan.title)

            # 2. 创建报名问卷（同一活动只建一次）
            form = get_tool("create_form").execute(
                ctx, idempotency_key=f"form:{activity_id}", title=f"{plan.title}报名表"
            )
            self._log_step(conn, activity_id, "create_form", "done", form["form_id"])

            # 3. 任务派发到人 + 消息通知（任务行与消息均幂等）
            for task in plan.tasks:
                exists = conn.execute(
                    "SELECT 1 FROM tasks WHERE activity_id = ? AND title = ? AND assignee = ?",
                    (activity_id, task.title, task.assignee),
                ).fetchone()
                if not exists:
                    conn.execute(
                        "INSERT INTO tasks (id, activity_id, title, assignee, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
                        (uuid.uuid4().hex[:12], activity_id, task.title, task.assignee, datetime.now().isoformat()),
                    )
                content = (
                    f"【{plan.title}】你被分配了任务「{task.title}」，"
                    f"活动时间 {plan.event_time}，请及时完成。"
                )
                get_tool("send_message").execute(
                    ctx,
                    idempotency_key=make_message_key(activity_id, task.assignee, content),
                    recipient=task.assignee,
                    content=content,
                )
            self._log_step(conn, activity_id, "assign_tasks", "done", f"{len(plan.tasks)} 项任务")

            # 4. 创建日历事件（ICS）
            event = get_tool("create_calendar_event").execute(
                ctx,
                idempotency_key=f"cal:{activity_id}",
                title=plan.title,
                event_time=plan.event_time,
                description=plan.description,
            )
            self._log_step(conn, activity_id, "create_calendar_event", "done", plan.event_time)

            # 5. 活动前定时提醒（可取消）
            remind_at = datetime.fromisoformat(plan.event_time) - timedelta(
                minutes=plan.remind_minutes_before
            )
            reminder = self.reminders.schedule(
                conn,
                activity_id,
                remind_at,
                f"活动「{plan.title}」将于 {plan.event_time} 开始，物料：{'、'.join(plan.materials)}，请各负责人就位！",
            )
            self._log_step(conn, activity_id, "schedule_reminder", "done", reminder["id"])

            return {
                "activity_id": activity_id,
                "plan": plan.model_dump(),
                "form_id": form["form_id"],
                "calendar_event": {"title": event["title"], "event_time": event["event_time"]},
                "reminder_id": reminder["id"],
            }
