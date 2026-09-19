import logging
import json
import uuid
from datetime import datetime, timedelta

from ..tools.base import ToolContext
from ..tools.message_tool import make_message_key
from ..tools.registry import get_tool
from ..config import settings
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
            workflow_row = conn.execute(
                "SELECT config_json FROM activity_workflows WHERE activity_id = ?", (activity_id,)
            ).fetchone()
            workflow = json.loads(workflow_row["config_json"]) if workflow_row else {
                "create_form": True,
                "assign_tasks": True,
                "create_calendar": True,
                "schedule_reminder": True,
                "publish_message": True,
            }

            # 1. 规划：一句话 → 结构化策划
            class_link = conn.execute(
                "SELECT class_id FROM activity_classes WHERE activity_id = ?", (activity_id,)
            ).fetchone()
            available_assignees = []
            if class_link:
                available_assignees = [row["display_name"] for row in conn.execute(
                    "SELECT a.display_name FROM class_members cm JOIN accounts a ON a.id = cm.account_id "
                    "WHERE cm.class_id = ? AND a.status = 'active' ORDER BY a.student_no, a.display_name",
                    (class_link["class_id"],),
                ).fetchall()]
            plan = planner.plan_activity(self.llm, raw_input, available_assignees)
            conn.execute(
                "UPDATE activities SET title = ?, plan_json = ? WHERE id = ?",
                (plan.title, plan.model_dump_json(), activity_id),
            )
            self._log_step(conn, activity_id, "generate_plan", "done", plan.title)

            # 2. 创建报名问卷（同一活动只建一次）
            form = None
            if workflow.get("create_form", True):
                form = get_tool("create_form").execute(
                    ctx,
                    idempotency_key=f"form:{activity_id}",
                    title=f"{plan.title}报名表",
                    fields=[field.model_dump() for field in plan.form_fields],
                )
                self._log_step(conn, activity_id, "create_form", "done", form["form_id"])

            # 3. 任务派发到人 + 消息通知（任务行与消息均幂等）
            for task in plan.tasks if workflow.get("assign_tasks", True) else []:
                exists = conn.execute(
                    "SELECT 1 FROM tasks WHERE activity_id = ? AND title = ? AND assignee = ?",
                    (activity_id, task.title, task.assignee),
                ).fetchone()
                if not exists:
                    task_id = uuid.uuid4().hex[:12]
                    conn.execute(
                        "INSERT INTO tasks (id, activity_id, title, assignee, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
                        (task_id, activity_id, task.title, task.assignee, datetime.now().isoformat()),
                    )
                    if class_link:
                        member = conn.execute(
                            "SELECT a.id FROM class_members cm JOIN accounts a ON a.id = cm.account_id "
                            "WHERE cm.class_id = ? AND a.display_name = ? LIMIT 1",
                            (class_link["class_id"], task.assignee),
                        ).fetchone()
                        if member:
                            conn.execute(
                                "INSERT INTO activity_task_assignees (task_id, account_id) VALUES (?, ?)",
                                (task_id, member["id"]),
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
            if workflow.get("assign_tasks", True):
                self._log_step(conn, activity_id, "assign_tasks", "done", f"{len(plan.tasks)} 项任务")

            # 4. 创建日历事件（ICS）
            event = None
            if workflow.get("create_calendar", True):
                event = get_tool("create_calendar_event").execute(
                    ctx,
                    idempotency_key=f"cal:{activity_id}",
                    title=plan.title,
                    event_time=plan.event_time,
                    description=plan.description,
                )
                self._log_step(conn, activity_id, "create_calendar_event", "done", plan.event_time)

            # 5. 活动前定时提醒（可取消）
            reminder = None
            if workflow.get("schedule_reminder", True):
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

            # 6. 若活动绑定了 QQ 群，则真实发布活动、报名链接与任务分工。
            channel = conn.execute(
                "SELECT group_openid FROM activity_channels WHERE activity_id = ?", (activity_id,)
            ).fetchone()
            if channel and workflow.get("publish_message", True):
                sections = [f"【发布】{plan.title}", f"时间：{plan.event_time}", plan.description]
                if workflow.get("assign_tasks", True) and plan.tasks:
                    task_lines = "\n".join(f"- {task.assignee}：{task.title}" for task in plan.tasks)
                    sections.append(f"任务分工：\n{task_lines}")
                if form:
                    sections.append(f"填写链接：{settings.public_base_url.rstrip('/')}/forms/{form['form_id']}")
                announcement = "\n\n".join(section for section in sections if section)
                try:
                    get_tool("send_qq_group_message").execute(
                        ctx,
                        idempotency_key=f"qq:announcement:{activity_id}",
                        group_openid=channel["group_openid"],
                        content=announcement,
                        kind="announcement",
                    )
                    self._log_step(conn, activity_id, "publish_qq", "done", "活动已发布到 QQ 群")
                except Exception as exc:
                    logger.exception("活动发布到 QQ 失败: %s", activity_id)
                    self._log_step(conn, activity_id, "publish_qq", "failed", str(exc)[:500])

            return {
                "activity_id": activity_id,
                "plan": plan.model_dump(),
                "form_id": form["form_id"] if form else None,
                "calendar_event": {"title": event["title"], "event_time": event["event_time"]} if event else None,
                "reminder_id": reminder["id"] if reminder else None,
            }
