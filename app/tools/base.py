from dataclasses import dataclass

from ..core.idempotency import execute_once
from ..core.retry import retry as retry_decorator


@dataclass
class ToolContext:
    """工具执行上下文：数据库连接 + 调用者身份。"""

    conn: object
    user_id: str = ""
    activity_id: str = ""


class Tool:
    """工具基类。execute() 统一封装：先重试（指数退避），再按幂等键去重。

    子类只需实现 run()；对外有副作用的工具在调用 execute 时必须传 idempotency_key。
    """

    name = "tool"
    description = ""
    max_attempts = 3

    def parameters(self) -> dict:
        """JSON Schema，供 LLM function calling 使用。"""
        return {"type": "object", "properties": {}}

    def run(self, ctx: ToolContext, **kwargs) -> dict:
        raise NotImplementedError

    def execute(self, ctx: ToolContext, idempotency_key: str | None = None, **kwargs) -> dict:
        call = retry_decorator(max_attempts=self.max_attempts)(
            lambda: self.run(ctx, **kwargs)
        )
        if idempotency_key:
            return execute_once(ctx.conn, idempotency_key, self.name, call)
        return call()
