import asyncio
import re

from ..config import settings


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
            "也可以直接用自然语言问我活动相关问题。"
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
            f"【{activity['title']}】\n状态：{activity['status']}\n"
            f"任务：已完成 {counts.get('done', 0)}/{total}\n报名：{signup_count} 人"
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
            "SELECT a.id, a.title, a.status FROM activities a "
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


async def process_group_message(db, service, llm, event) -> str | None:
    if not service.record_inbound(event):
        return None
    match = re.search(r"绑定\s*(\d{6})", event.content)
    if match:
        user_id = service.bind_with_code(match.group(1), event.chat_id)
        return (
            f"绑定成功！活动管家已连接此群，网页用户 {user_id} 创建活动后会自动发布。"
            if user_id else "绑定码无效或已过期，请回到活动管家网页获取新绑定码。"
        )
    reply = build_command_reply(db, event)
    if reply is not None:
        return reply
    context = build_activity_context(db, event.chat_id)
    try:
        return await asyncio.to_thread(
            llm.generate_assistant_reply, event.content, context
        )
    except Exception:
        return "我暂时无法调用 AI，请稍后再试。发送“帮助”仍可使用活动指令。"
