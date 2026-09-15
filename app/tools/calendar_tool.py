import uuid
from datetime import datetime

from .base import Tool, ToolContext


def _to_ics_time(iso_time: str) -> str:
    return datetime.fromisoformat(iso_time).strftime("%Y%m%dT%H%M%S")


class CreateCalendarEventTool(Tool):
    """创建日历事件（Mock：生成 ICS 文本，可导入系统日历或对接日历 API）。"""

    name = "create_calendar_event"
    description = "创建日历事件，返回 ICS 文本"

    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "event_time": {"type": "string", "description": "ISO 格式开始时间"},
                "description": {"type": "string"},
            },
            "required": ["title", "event_time"],
        }

    def run(self, ctx: ToolContext, title: str, event_time: str, description: str = "") -> dict:
        ics = "\r\n".join([
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "BEGIN:VEVENT",
            f"UID:{uuid.uuid4().hex}",
            f"DTSTART:{_to_ics_time(event_time)}",
            f"SUMMARY:{title}",
            f"DESCRIPTION:{description}",
            "END:VEVENT",
            "END:VCALENDAR",
        ])
        return {"title": title, "event_time": event_time, "ics": ics}
