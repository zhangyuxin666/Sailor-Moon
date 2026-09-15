from .calendar_tool import CreateCalendarEventTool
from .form_tool import CreateFormTool, FormStatsTool
from .message_tool import SendMessageTool

TOOLS = {
    t.name: t
    for t in [SendMessageTool(), CreateFormTool(), FormStatsTool(), CreateCalendarEventTool()]
}


def get_tool(name: str):
    return TOOLS[name]


def tool_schemas() -> list[dict]:
    """供 LLM function calling 使用的工具 schema 列表。"""
    return [
        {"name": t.name, "description": t.description, "parameters": t.parameters()}
        for t in TOOLS.values()
    ]
