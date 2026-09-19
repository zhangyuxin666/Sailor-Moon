from datetime import datetime
from types import SimpleNamespace

from app.config import settings
from app.integrations.qq import QQBotService
from app.tools.base import ToolContext
from app.tools.qq_tool import SendQQGroupMessageTool
from app.qq_gateway import build_command_reply
from qqbot_agent_sdk import EventParser


def test_binding_code_connects_group(db, monkeypatch):
    monkeypatch.setattr(settings, "qq_bot_app_id", "test-app")
    monkeypatch.setattr(settings, "qq_bot_app_secret", "test-secret")
    service = QQBotService(db)

    code = service.status("alice")["binding_code"]
    assert len(code) == 6
    assert service.bind_with_code(code, "group-openid") == "alice"
    status = service.status("alice")
    assert status["groups"][0]["group_openid"] == "group-openid"
    assert status["binding_code"] is None


def test_bot_introduction_is_valid_chinese(db):
    event = SimpleNamespace(content="你是什么", chat_id="group-1")
    reply = build_command_reply(db, event)
    assert "活动管家" in reply
    assert "????" not in reply


def test_unknown_message_is_routed_to_llm(db):
    event = SimpleNamespace(content="帮我想个活动口号", chat_id="group-1")
    assert build_command_reply(db, event) is None


def test_full_group_message_event_is_supported():
    event = EventParser().parse(
        "GROUP_MESSAGE_CREATE",
        {
            "id": "message-1",
            "group_openid": "group-1",
            "content": "@活动管家 你是什么",
            "timestamp": "2026-09-19T20:00:00+08:00",
            "author": {"member_openid": "member-1"},
        },
    )
    assert event is not None
    assert event.chat_scope == "group"
    assert event.content == "你是什么"


def test_qq_tool_records_delivery_once(db, monkeypatch):
    monkeypatch.setattr(settings, "qq_bot_app_id", "test-app")
    monkeypatch.setattr(settings, "qq_bot_app_secret", "test-secret")
    tool = SendQQGroupMessageTool(db)
    monkeypatch.setattr(tool.qq, "send_group", lambda *_args, **_kwargs: {"id": "qq-message-1"})

    with db.connect() as conn:
        conn.execute(
            "INSERT INTO activities (id, user_id, title, raw_input, status, created_at) "
            "VALUES ('activity-1', 'alice', '测试', '测试', 'ready', ?)",
            (datetime.now().isoformat(),),
        )
        context = ToolContext(conn=conn, user_id="alice", activity_id="activity-1")
        first = tool.execute(
            context,
            idempotency_key="qq:test:1",
            group_openid="group-openid",
            content="活动通知",
            kind="manual",
        )
        second = tool.execute(
            context,
            idempotency_key="qq:test:1",
            group_openid="group-openid",
            content="活动通知",
            kind="manual",
        )
        deliveries = conn.execute(
            "SELECT * FROM delivery_events WHERE activity_id = 'activity-1'"
        ).fetchall()

    assert first == second
    assert first["external_id"] == "qq-message-1"
    assert len(deliveries) == 1
    assert deliveries[0]["status"] == "sent"


def test_qq_tool_uses_latest_group_message_as_fallback(db, monkeypatch):
    monkeypatch.setattr(settings, "qq_bot_app_id", "test-app")
    monkeypatch.setattr(settings, "qq_bot_app_secret", "test-secret")
    tool = SendQQGroupMessageTool(db)
    captured = {}

    def fake_send(*_args, **kwargs):
        captured.update(kwargs)
        return {"id": "qq-message-2", "delivery_mode": "recent_message_reply"}

    monkeypatch.setattr(tool.qq, "send_group", fake_send)
    with db.connect() as conn:
        conn.execute(
            "INSERT INTO qq_inbound_events (message_id, group_openid, content, created_at) "
            "VALUES ('inbound-1', 'group-1', '你好', ?)",
            (datetime.now().isoformat(),),
        )
        context = ToolContext(conn=conn, user_id="alice", activity_id="activity-2")
        tool.execute(
            context,
            idempotency_key="qq:test:2",
            group_openid="group-1",
            content="提醒",
        )

    assert captured["fallback_reply_to"] == "inbound-1"
