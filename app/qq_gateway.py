import asyncio
import logging
import re

import httpx
from qqbot_agent_sdk import EventParser, QQApiClient, QQWebSocket, WSCallbacks

from .config import settings
from .agent.llm import get_llm
from .integrations.qq import QQBotService
from .models.database import Database

logging.basicConfig(
    level=logging.INFO,
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("qq_gateway.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger(__name__)

# QQ 开启“获取群内全部消息”后会投递 GROUP_MESSAGE_CREATE，
# 当前官方 SDK 1.2.2 尚未把该事件注册到解析器。
EventParser._EVENT_HANDLERS.setdefault(
    "GROUP_MESSAGE_CREATE", EventParser._parse_group
)


def build_command_reply(db, event) -> str | None:
    content = event.content.strip()
    if any(keyword in content for keyword in ("你是什么", "你是谁", "介绍一下")):
        return (
            "我是活动管家，一个社团和班级活动组织助手。\n"
            "我可以自动发布报名、同步任务、催办负责人、发送定时提醒和汇总活动进度。\n"
            "发送“帮助”查看可用指令。"
        )
    if content in {"帮助", "help", "/help", "菜单"}:
        return (
            "【活动管家指令】\n"
            "• 活动状态：查看最近活动进度\n"
            "• 报名链接：获取最近活动报名入口\n"
            "• 帮助：查看本指令列表\n"
            "活动创建、任务催办和完整监控请在活动管家网页操作。"
        )
    if content in {"活动状态", "进度", "当前活动"}:
        with db.connect() as conn:
            activity = conn.execute(
                "SELECT a.id, a.title, a.status FROM activities a "
                "JOIN activity_channels c ON c.activity_id = a.id "
                "WHERE c.group_openid = ? ORDER BY a.created_at DESC LIMIT 1",
                (event.chat_id,),
            ).fetchone()
            if not activity:
                return "本群还没有活动，请先在活动管家网页发起活动。"
            tasks = conn.execute(
                "SELECT status, COUNT(*) AS count FROM tasks WHERE activity_id = ? GROUP BY status",
                (activity["id"],),
            ).fetchall()
            form = conn.execute(
                "SELECT id FROM forms WHERE activity_id = ? LIMIT 1", (activity["id"],)
            ).fetchone()
            signup_count = 0
            if form:
                signup_count = conn.execute(
                    "SELECT COUNT(*) AS count FROM registrations WHERE form_id = ?", (form["id"],)
                ).fetchone()["count"]
        counts = {row["status"]: row["count"] for row in tasks}
        total = sum(counts.values())
        return (
            f"【{activity['title']}】\n"
            f"状态：{activity['status']}\n"
            f"任务：已完成 {counts.get('done', 0)}/{total}\n"
            f"报名：{signup_count} 人"
        )
    if content in {"报名链接", "报名", "怎么报名"}:
        with db.connect() as conn:
            form = conn.execute(
                "SELECT f.id, a.title FROM forms f "
                "JOIN activities a ON a.id = f.activity_id "
                "JOIN activity_channels c ON c.activity_id = a.id "
                "WHERE c.group_openid = ? ORDER BY a.created_at DESC LIMIT 1",
                (event.chat_id,),
            ).fetchone()
        if not form:
            return "本群目前没有可用的活动报名表。"
        return f"【{form['title']}】报名链接：{settings.public_base_url.rstrip('/')}/forms/{form['id']}"
    return None


def build_activity_context(db, group_openid: str) -> str:
    with db.connect() as conn:
        activity = conn.execute(
            "SELECT a.id, a.title, a.status, a.raw_input, a.plan_json FROM activities a "
            "JOIN activity_channels c ON c.activity_id = a.id "
            "WHERE c.group_openid = ? ORDER BY a.created_at DESC LIMIT 1",
            (group_openid,),
        ).fetchone()
        if not activity:
            return "本群尚未创建活动。"
        tasks = conn.execute(
            "SELECT title, assignee, status FROM tasks WHERE activity_id = ?",
            (activity["id"],),
        ).fetchall()
        form = conn.execute(
            "SELECT id FROM forms WHERE activity_id = ? LIMIT 1", (activity["id"],)
        ).fetchone()
        signup_count = 0
        if form:
            signup_count = conn.execute(
                "SELECT COUNT(*) AS count FROM registrations WHERE form_id = ?", (form["id"],)
            ).fetchone()["count"]
    task_text = "；".join(
        f"{task['title']}（{task['assignee']}，{task['status']}）" for task in tasks
    ) or "暂无任务"
    return (
        f"活动：{activity['title']}；状态：{activity['status']}；"
        f"报名人数：{signup_count}；任务：{task_text}"
    )


async def run_gateway():
    db = Database(settings.database_url)
    service = QQBotService(db)
    if not service.configured:
        service.set_runtime_state("unconfigured", "请先填写 QQ_BOT_APP_ID 和 QQ_BOT_APP_SECRET")
        logger.error("QQ 机器人未配置，请检查 .env")
        return

    api = QQApiClient(settings.qq_bot_app_id, settings.qq_bot_app_secret, log_tag="ActivityAssistant")
    llm = get_llm()
    session_id = None
    last_seq = None

    async def on_message(event_type: str, raw: dict):
        logger.info("收到 QQ 事件: %s", event_type)
        event = EventParser().parse(event_type, raw)
        if not event:
            logger.warning("QQ 事件无法解析: %s", event_type)
            return
        if event.chat_scope != "group":
            return
        service.record_inbound(event)
        match = re.search(r"绑定\s*(\d{6})", event.content)
        if match:
            user_id = service.bind_with_code(match.group(1), event.chat_id)
            reply = (
                f"绑定成功！活动管家已连接此群，网页用户 {user_id} 创建活动后会自动发布。"
                if user_id else "绑定码无效或已过期，请回到活动管家网页获取新绑定码。"
            )
            await api.send_text("group", event.chat_id, reply, reply_to=event.message_id, markdown=False)
            return
        reply = build_command_reply(db, event)
        if reply is None:
            context = build_activity_context(db, event.chat_id)
            try:
                reply = await asyncio.to_thread(
                    llm.generate_assistant_reply, event.content, context
                )
            except Exception:
                logger.exception("生成 QQ 智能回复失败")
                reply = "我暂时无法调用 AI，请稍后再试。发送“帮助”仍可使用活动指令。"
        await api.send_text(
            "group", event.chat_id, reply, reply_to=event.message_id, markdown=False
        )

    def get_session():
        return session_id, last_seq

    def set_session(new_session_id, new_last_seq):
        nonlocal session_id, last_seq
        session_id, last_seq = new_session_id, new_last_seq

    def on_connected():
        service.set_runtime_state("online", "QQ 网关连接正常")
        logger.info("QQ 网关已连接")

    def on_disconnected():
        service.set_runtime_state("reconnecting", "连接中断，正在自动重连")

    def on_fatal_error(code: str, message: str):
        service.set_runtime_state("error", f"{code}: {message}")

    async with httpx.AsyncClient() as http_client:
        api.setup(http_client)
        websocket = QQWebSocket(
            callbacks=WSCallbacks(
                on_message_event=on_message,
                on_connected=on_connected,
                on_disconnected=on_disconnected,
                on_fatal_error=on_fatal_error,
                get_token=api.ensure_token_sync,
                get_session=get_session,
                set_session=set_session,
                set_heartbeat_interval=lambda _seconds: None,
                clear_token=api.clear_token,
                fail_pending=lambda _reason: None,
                get_gateway_url=api.get_gateway_url_sync,
                on_heartbeat_ack=lambda: service.set_runtime_state(
                    "online", "QQ 网关连接正常，心跳正常"
                ),
            ),
            log_tag="ActivityAssistant",
        )
        try:
            await api.ensure_token()
            gateway_url = await api.get_gateway_url()
            websocket.start(gateway_url, asyncio.get_running_loop())
            await asyncio.Event().wait()
        finally:
            websocket.stop()
            service.set_runtime_state("offline", "QQ 网关已停止")


if __name__ == "__main__":
    asyncio.run(run_gateway())
