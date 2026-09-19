import asyncio
import json
import random
import uuid
from datetime import datetime, timedelta

import httpx
from qqbot_agent_sdk import QQApiClient

from ..config import settings
from ..core.exceptions import NotFoundError, PermissionDeniedError, ToolError


class QQBotService:
    """QQ 官方机器人绑定、状态和消息投递服务。"""

    def __init__(self, db, api_factory=QQApiClient):
        self.db = db
        self.api_factory = api_factory

    @property
    def configured(self) -> bool:
        return bool(settings.qq_bot_app_id and settings.qq_bot_app_secret)

    def set_runtime_state(self, status: str, detail: str = ""):
        payload = json.dumps({"status": status, "detail": detail}, ensure_ascii=False)
        now = datetime.now().isoformat()
        with self.db.connect() as conn:
            row = conn.execute("SELECT 1 FROM integration_state WHERE key = 'qq_gateway'").fetchone()
            if row:
                conn.execute(
                    "UPDATE integration_state SET value_json = ?, updated_at = ? WHERE key = 'qq_gateway'",
                    (payload, now),
                )
            else:
                conn.execute(
                    "INSERT INTO integration_state (key, value_json, updated_at) VALUES ('qq_gateway', ?, ?)",
                    (payload, now),
                )

    def status(self, user_id: str) -> dict:
        with self.db.connect() as conn:
            state = conn.execute(
                "SELECT value_json, updated_at FROM integration_state WHERE key = 'qq_gateway'"
            ).fetchone()
            groups = [dict(row) for row in conn.execute(
                "SELECT group_openid, group_label, status, last_seen_at FROM qq_bindings "
                "WHERE user_id = ? AND status = 'active' ORDER BY created_at",
                (user_id,),
            ).fetchall()]
        runtime = json.loads(state["value_json"]) if state else {"status": "offline", "detail": "网关未启动"}
        return {
            "configured": self.configured,
            "gateway": runtime,
            "gateway_updated_at": state["updated_at"] if state else None,
            "groups": groups,
            "binding_code": self._get_or_create_binding_code(user_id) if self.configured and not groups else None,
            "public_base_url": settings.public_base_url.rstrip("/"),
        }

    def _get_or_create_binding_code(self, user_id: str) -> str:
        now = datetime.now()
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT code, expires_at FROM qq_binding_codes WHERE user_id = ? ORDER BY created_at DESC",
                (user_id,),
            ).fetchone()
            if row and datetime.fromisoformat(row["expires_at"]) > now:
                return row["code"]
            conn.execute("DELETE FROM qq_binding_codes WHERE user_id = ?", (user_id,))
            while True:
                code = str(random.SystemRandom().randint(100000, 999999))
                if not conn.execute("SELECT 1 FROM qq_binding_codes WHERE code = ?", (code,)).fetchone():
                    break
            conn.execute(
                "INSERT INTO qq_binding_codes (code, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                (code, user_id, (now + timedelta(minutes=30)).isoformat(), now.isoformat()),
            )
            return code

    def bind_with_code(self, code: str, group_openid: str, group_label: str = "") -> str | None:
        now = datetime.now()
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT user_id, expires_at FROM qq_binding_codes WHERE code = ?", (code,)
            ).fetchone()
            if not row or datetime.fromisoformat(row["expires_at"]) <= now:
                return None
            existing = conn.execute(
                "SELECT 1 FROM qq_bindings WHERE group_openid = ?", (group_openid,)
            ).fetchone()
            values = (row["user_id"], group_label or "已绑定 QQ 群", now.isoformat(), group_openid)
            if existing:
                conn.execute(
                    "UPDATE qq_bindings SET user_id = ?, group_label = ?, status = 'active', last_seen_at = ? "
                    "WHERE group_openid = ?",
                    values,
                )
            else:
                conn.execute(
                    "INSERT INTO qq_bindings (group_openid, user_id, group_label, status, last_seen_at, created_at) "
                    "VALUES (?, ?, ?, 'active', ?, ?)",
                    (group_openid, row["user_id"], group_label or "已绑定 QQ 群", now.isoformat(), now.isoformat()),
                )
            conn.execute("DELETE FROM qq_binding_codes WHERE code = ?", (code,))
            return row["user_id"]

    def touch_group(self, group_openid: str):
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE qq_bindings SET last_seen_at = ? WHERE group_openid = ?",
                (datetime.now().isoformat(), group_openid),
            )

    def record_inbound(self, event):
        with self.db.connect() as conn:
            if conn.execute(
                "SELECT 1 FROM qq_inbound_events WHERE message_id = ?", (event.message_id,)
            ).fetchone():
                return False
            conn.execute(
                "INSERT INTO qq_inbound_events (message_id, group_openid, sender_openid, sender_name, content, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (event.message_id, event.chat_id, event.user_id, event.user_name or "", event.content, event.timestamp or datetime.now().isoformat()),
            )
        self.touch_group(event.chat_id)
        return True

    def attach_activity(self, conn, user_id: str, activity_id: str) -> str | None:
        row = conn.execute(
            "SELECT group_openid FROM qq_bindings WHERE user_id = ? AND status = 'active' ORDER BY created_at LIMIT 1",
            (user_id,),
        ).fetchone()
        if not row:
            return None
        conn.execute(
            "INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at) VALUES (?, ?, ?, ?)",
            (activity_id, user_id, row["group_openid"], datetime.now().isoformat()),
        )
        return row["group_openid"]

    def get_activity_group(self, conn, activity_id: str) -> str | None:
        row = conn.execute(
            "SELECT group_openid FROM activity_channels WHERE activity_id = ?", (activity_id,)
        ).fetchone()
        return row["group_openid"] if row else None

    async def send_group_async(
        self,
        group_openid: str,
        content: str,
        reply_to: str | None = None,
        fallback_reply_to: str | None = None,
    ) -> dict:
        if not self.configured:
            raise ToolError("QQ 机器人尚未配置 AppID 和 AppSecret")
        api = self.api_factory(
            app_id=settings.qq_bot_app_id,
            client_secret=settings.qq_bot_app_secret,
            log_tag="ActivityAssistant",
        )
        async with httpx.AsyncClient() as client:
            api.setup(client)
            message = api.build_text_body(content, reply_to=reply_to, markdown=False)
            try:
                return await api.post_group_message(group_openid, message)
            except RuntimeError as proactive_error:
                if reply_to or not fallback_reply_to:
                    raise
                fallback = api.build_text_body(
                    content, reply_to=fallback_reply_to, markdown=False
                )
                try:
                    result = await api.post_group_message(group_openid, fallback)
                except Exception as fallback_error:
                    raise ToolError(
                        "机器人未开通群主动消息权限，且最近的群消息已无法用于回复。"
                        "请先在群里 @机器人 发送任意消息后重试，或在 QQ 开放平台开启主动发言权限。"
                    ) from fallback_error
                result["delivery_mode"] = "recent_message_reply"
                result["proactive_error"] = str(proactive_error)
                return result

    def send_group(
        self,
        group_openid: str,
        content: str,
        reply_to: str | None = None,
        fallback_reply_to: str | None = None,
    ) -> dict:
        return asyncio.run(
            self.send_group_async(group_openid, content, reply_to, fallback_reply_to)
        )

    def send_activity_message(self, user_id: str, activity_id: str, content: str, kind: str = "manual") -> dict:
        with self.db.connect() as conn:
            activity = conn.execute(
                "SELECT user_id FROM activities WHERE id = ?", (activity_id,)
            ).fetchone()
            if not activity:
                raise NotFoundError(f"活动不存在: {activity_id}")
            if activity["user_id"] != user_id:
                raise PermissionDeniedError("只能操作自己创建的活动")
            group_openid = self.get_activity_group(conn, activity_id)
            if not group_openid:
                raise NotFoundError("该活动没有绑定 QQ 群")
        result = self.send_group(group_openid, content)
        self.record_delivery(activity_id, kind, group_openid, content, "sent", result.get("id"))
        return result

    def record_delivery(
        self, activity_id: str, kind: str, target: str, content: str, status: str,
        external_id: str | None = None, error: str | None = None,
    ) -> str:
        delivery_id = uuid.uuid4().hex[:12]
        now = datetime.now().isoformat()
        with self.db.connect() as conn:
            conn.execute(
                "INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, external_id, error, created_at, completed_at) "
                "VALUES (?, ?, ?, 'qq_group', ?, ?, ?, ?, ?, ?, ?)",
                (delivery_id, activity_id, kind, target, content, status, external_id, error, now, now),
            )
        return delivery_id
