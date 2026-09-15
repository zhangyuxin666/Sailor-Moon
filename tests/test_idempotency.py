from app.tools.base import ToolContext
from app.tools.message_tool import make_message_key
from app.tools.registry import get_tool


def test_same_idempotency_key_sends_message_once(db):
    """同一幂等键执行两次，消息只发一次，第二次返回缓存结果。"""
    with db.connect() as conn:
        ctx = ToolContext(conn=conn, activity_id="act1")
        key = make_message_key("act1", "张三", "记得带横幅")
        tool = get_tool("send_message")
        r1 = tool.execute(ctx, idempotency_key=key, recipient="张三", content="记得带横幅")
        r2 = tool.execute(ctx, idempotency_key=key, recipient="张三", content="记得带横幅")
        assert r1 == r2
        count = conn.execute("SELECT COUNT(*) AS c FROM messages").fetchone()["c"]
        assert count == 1


def test_different_keys_send_separately(db):
    """不同内容/接收人的消息不受影响。"""
    with db.connect() as conn:
        ctx = ToolContext(conn=conn, activity_id="act1")
        tool = get_tool("send_message")
        for recipient, content in [("张三", "A"), ("李四", "A"), ("张三", "B")]:
            tool.execute(
                ctx,
                idempotency_key=make_message_key("act1", recipient, content),
                recipient=recipient,
                content=content,
            )
        count = conn.execute("SELECT COUNT(*) AS c FROM messages").fetchone()["c"]
        assert count == 3
