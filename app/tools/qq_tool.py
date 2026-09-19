import uuid
from datetime import datetime

from ..integrations.qq import QQBotService
from .base import Tool, ToolContext


class SendQQGroupMessageTool(Tool):
    name = "send_qq_group_message"
    description = "通过 QQ 官方机器人向活动群发送消息"
    max_attempts = 1

    def __init__(self, db):
        self.qq = QQBotService(db)

    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "group_openid": {"type": "string"},
                "content": {"type": "string"},
                "kind": {"type": "string"},
            },
            "required": ["group_openid", "content"],
        }

    def run(self, ctx: ToolContext, group_openid: str, content: str, kind: str = "notification") -> dict:
        delivery_id = uuid.uuid4().hex[:12]
        now = datetime.now().isoformat()
        inbound = ctx.conn.execute(
            "SELECT message_id FROM qq_inbound_events WHERE group_openid = ? "
            "ORDER BY created_at DESC LIMIT 1",
            (group_openid,),
        ).fetchone()
        try:
            result = self.qq.send_group(
                group_openid,
                content,
                fallback_reply_to=inbound["message_id"] if inbound else None,
            )
        except Exception as exc:
            ctx.conn.execute(
                "INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, error, created_at, completed_at) "
                "VALUES (?, ?, ?, 'qq_group', ?, ?, 'failed', ?, ?, ?)",
                (delivery_id, ctx.activity_id, kind, group_openid, content, str(exc)[:1000], now, now),
            )
            raise
        ctx.conn.execute(
            "INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, external_id, created_at, completed_at) "
            "VALUES (?, ?, ?, 'qq_group', ?, ?, 'sent', ?, ?, ?)",
            (delivery_id, ctx.activity_id, kind, group_openid, content, result.get("id"), now, now),
        )
        return {
            "delivery_id": delivery_id,
            "external_id": result.get("id"),
            "recipient": group_openid,
            "status": "sent",
        }
