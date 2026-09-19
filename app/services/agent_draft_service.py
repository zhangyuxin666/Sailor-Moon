import json
import uuid
from datetime import datetime

from ..core.audit import write_audit
from ..core.exceptions import NotFoundError, PermissionDeniedError
from ..models.schemas import TodoCreateIn, WorkDraft


class AgentDraftService:
    def __init__(self, db, llm, classroom_service, activity_service):
        self.db = db
        self.llm = llm
        self.classroom = classroom_service
        self.activities = activity_service

    def create(self, account: dict, class_id: str, raw_input: str) -> dict:
        if account["role"] != "manager":
            raise PermissionDeniedError("只有管理者可以使用发布 Agent")
        with self.db.connect() as conn:
            classroom = self.classroom._require_class_manager(conn, class_id, account)
            members = conn.execute(
                "SELECT a.display_name FROM class_members cm JOIN accounts a ON a.id = cm.account_id "
                "WHERE cm.class_id = ? AND a.status = 'active' ORDER BY a.student_no, a.display_name",
                (class_id,),
            ).fetchall()
        class_context = (
            f"班级：{classroom['name']}；成员数：{len(members)}；"
            f"成员：{'、'.join(row['display_name'] for row in members)}"
        )
        draft = self.llm.analyze_work_request(raw_input, class_context)
        draft_id = uuid.uuid4().hex[:12]
        now = datetime.now().isoformat()
        with self.db.connect() as conn:
            conn.execute(
                "INSERT INTO agent_drafts (id, organization_id, class_id, creator_id, raw_input, intent_type, complexity, draft_json, status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?)",
                (
                    draft_id, classroom["organization_id"], class_id, account["id"],
                    raw_input, draft.intent_type, draft.complexity,
                    draft.model_dump_json(), now, now,
                ),
            )
            write_audit(
                conn, "agent.draft_create", account["id"], classroom["organization_id"],
                "agent_draft", draft_id,
                {"intent_type": draft.intent_type, "complexity": draft.complexity},
            )
        return self.get(account, draft_id)

    def get(self, account: dict, draft_id: str) -> dict:
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT d.*, c.name AS class_name FROM agent_drafts d JOIN classes c ON c.id = d.class_id "
                "JOIN organization_members om ON om.organization_id = d.organization_id "
                "WHERE d.id = ? AND om.account_id = ?",
                (draft_id, account["id"]),
            ).fetchone()
        if not row:
            raise NotFoundError("草案不存在")
        result = dict(row)
        result["draft"] = json.loads(result.pop("draft_json"))
        return result

    def list(self, account: dict) -> list[dict]:
        with self.db.connect() as conn:
            rows = conn.execute(
                "SELECT d.id, d.intent_type, d.complexity, d.status, d.result_type, d.result_id, "
                "d.created_at, d.updated_at, c.name AS class_name, d.draft_json "
                "FROM agent_drafts d JOIN classes c ON c.id = d.class_id "
                "JOIN organization_members om ON om.organization_id = d.organization_id "
                "WHERE om.account_id = ? ORDER BY d.created_at DESC LIMIT 100",
                (account["id"],),
            ).fetchall()
        items = []
        for row in rows:
            item = dict(row)
            data = json.loads(item.pop("draft_json"))
            item["title"] = data.get("title", "")
            item["summary"] = data.get("summary", "")
            items.append(item)
        return items

    def update(self, account: dict, draft_id: str, draft: WorkDraft) -> dict:
        current = self.get(account, draft_id)
        if current["status"] != "draft":
            raise ValueError("只有未发布草案可以修改")
        now = datetime.now().isoformat()
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE agent_drafts SET intent_type = ?, complexity = ?, draft_json = ?, updated_at = ? WHERE id = ?",
                (draft.intent_type, draft.complexity, draft.model_dump_json(), now, draft_id),
            )
            write_audit(
                conn, "agent.draft_update", account["id"], current["organization_id"],
                "agent_draft", draft_id,
            )
        return self.get(account, draft_id)

    def publish(self, account: dict, draft_id: str) -> dict:
        current = self.get(account, draft_id)
        if current["status"] == "published":
            return {
                "status": "published", "result_type": current["result_type"],
                "result_id": current["result_id"], "idempotent": True,
            }
        if current["status"] != "draft":
            raise ValueError("草案正在发布，请勿重复操作")
        draft = WorkDraft(**current["draft"])
        with self.db.connect() as conn:
            self.classroom._require_class_manager(conn, current["class_id"], account)
            conn.execute(
                "UPDATE agent_drafts SET status = 'publishing', updated_at = ? WHERE id = ? AND status = 'draft'",
                (datetime.now().isoformat(), draft_id),
            )
        try:
            result_type, result_id = self._execute(account, current, draft)
        except Exception:
            with self.db.connect() as conn:
                conn.execute(
                    "UPDATE agent_drafts SET status = 'draft', updated_at = ? WHERE id = ?",
                    (datetime.now().isoformat(), draft_id),
                )
            raise
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE agent_drafts SET status = 'published', result_type = ?, result_id = ?, updated_at = ? WHERE id = ?",
                (result_type, result_id, datetime.now().isoformat(), draft_id),
            )
            write_audit(
                conn, "agent.draft_publish", account["id"], current["organization_id"],
                result_type, result_id, {"draft_id": draft_id},
            )
        return {"status": "published", "result_type": result_type, "result_id": result_id}

    def _execute(self, account: dict, current: dict, draft: WorkDraft) -> tuple[str, str]:
        if draft.intent_type == "assignment":
            if not draft.deadline:
                raise ValueError("发布作业前必须确认截止时间")
            todo = self.classroom.create_todo(
                account,
                current["class_id"],
                TodoCreateIn(
                    title=draft.title,
                    description=draft.description or draft.summary,
                    deadline=draft.deadline,
                    kind="homework",
                ),
            )
            return "todo", todo["id"]
        if draft.intent_type in {"activity", "survey"}:
            text = f"{draft.title}。{draft.description or draft.summary}"
            queued = self.activities.queue_activity(
                account["username"], text, draft.workflow.publish_message,
                current["class_id"], draft.workflow.model_dump(),
            )
            return "activity", queued["activity_id"]
        with self.db.connect() as conn:
            classroom = self.classroom._require_class_manager(conn, current["class_id"], account)
        if not classroom["qq_group_openid"]:
            raise ValueError("班级尚未绑定 QQ 群，无法发布通知")
        result = self.classroom.qq.send_group(
            classroom["qq_group_openid"], f"【班级通知】{draft.title}\n{draft.description or draft.summary}"
        )
        return "notice", result.get("id") or uuid.uuid4().hex[:12]
