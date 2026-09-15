import json
from datetime import datetime


def execute_once(conn, key: str, tool_name: str, fn):
    """幂等执行：同一 key 只真正执行一次，后续命中直接返回缓存结果。

    用于防止 Agent 重试/重跑导致重复发消息、重复建表单等副作用。
    fn 抛异常时不落 key，保证失败后仍可重试。
    """
    row = conn.execute(
        "SELECT result_json FROM idempotency_keys WHERE key = ?", (key,)
    ).fetchone()
    if row:
        return json.loads(row["result_json"])
    result = fn()
    conn.execute(
        "INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, ?, ?, ?)",
        (key, tool_name, json.dumps(result, ensure_ascii=False), datetime.now().isoformat()),
    )
    return result
