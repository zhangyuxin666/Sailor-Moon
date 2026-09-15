import hashlib
import logging
from datetime import datetime

from .base import Tool, ToolContext

logger = logging.getLogger(__name__)


def make_message_key(activity_id: str, recipient: str, content: str) -> str:
    """消息幂等键：同一活动给同一人发同样内容，只发一次。"""
    digest = hashlib.sha1(content.encode()).hexdigest()[:12]
    return f"msg:{activity_id}:{recipient}:{digest}"


class SendMessageTool(Tool):
    """发消息工具。Mock 实现：写入 messages 表 + 打日志。

    接入真实通道（企业微信/邮件/短信）时只需替换 run() 内的投递逻辑。
    """

    name = "send_message"
    description = "给指定接收人发送活动通知消息"

    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "recipient": {"type": "string", "description": "接收人"},
                "content": {"type": "string", "description": "消息内容"},
            },
            "required": ["recipient", "content"],
        }

    def run(self, ctx: ToolContext, recipient: str, content: str) -> dict:
        logger.info("[MockMessage] -> %s: %s", recipient, content)
        cur = ctx.conn.execute(
            "INSERT INTO messages (activity_id, recipient, content, created_at) VALUES (?, ?, ?, ?)",
            (ctx.activity_id, recipient, content, datetime.now().isoformat()),
        )
        return {"message_id": cur.lastrowid, "recipient": recipient, "status": "sent"}
