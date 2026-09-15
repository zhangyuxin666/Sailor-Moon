import json
import uuid
from datetime import datetime

from .base import Tool, ToolContext

DEFAULT_FIELDS = [
    {"name": "name", "label": "姓名"},
    {"name": "contact", "label": "联系方式"},
]


class CreateFormTool(Tool):
    """创建报名问卷（Mock：写入 forms 表，真实实现可替换为问卷星/金数据 API）。"""

    name = "create_form"
    description = "为活动创建报名问卷"

    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {"title": {"type": "string", "description": "问卷标题"}},
            "required": ["title"],
        }

    def run(self, ctx: ToolContext, title: str) -> dict:
        form_id = uuid.uuid4().hex[:12]
        ctx.conn.execute(
            "INSERT INTO forms (id, activity_id, title, fields_json, created_at) VALUES (?, ?, ?, ?, ?)",
            (form_id, ctx.activity_id, title, json.dumps(DEFAULT_FIELDS, ensure_ascii=False), datetime.now().isoformat()),
        )
        return {"form_id": form_id, "title": title}


class FormStatsTool(Tool):
    """报名问卷统计。"""

    name = "form_stats"
    description = "统计报名问卷的提交数据"

    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {"form_id": {"type": "string", "description": "问卷 ID"}},
            "required": ["form_id"],
        }

    def run(self, ctx: ToolContext, form_id: str) -> dict:
        rows = ctx.conn.execute(
            "SELECT name, contact, created_at FROM registrations WHERE form_id = ? ORDER BY id",
            (form_id,),
        ).fetchall()
        return {
            "form_id": form_id,
            "count": len(rows),
            "registrations": [dict(r) for r in rows],
        }
