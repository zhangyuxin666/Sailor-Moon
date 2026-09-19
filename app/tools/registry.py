from .calendar_tool import CreateCalendarEventTool
from .form_tool import CreateFormTool, FormStatsTool
from .message_tool import SendMessageTool
from .qq_tool import SendQQGroupMessageTool

TOOLS = {}


def configure_tools(db):
    tools = [
        SendMessageTool(), CreateFormTool(), FormStatsTool(), CreateCalendarEventTool(),
        SendQQGroupMessageTool(db),
    ]
    TOOLS.clear()
    TOOLS.update({tool.name: tool for tool in tools})


def get_tool(name: str):
    if not TOOLS:
        raise RuntimeError("工具注册表尚未初始化")
    return TOOLS[name]


def tool_schemas() -> list[dict]:
    """供 LLM function calling 使用的工具 schema 列表。"""
    return [
        {"name": t.name, "description": t.description, "parameters": t.parameters()}
        for t in TOOLS.values()
    ]
