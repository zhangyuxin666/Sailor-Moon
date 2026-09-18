import uuid
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from ..config import settings

from .base import Tool, ToolContext


def _to_ics_time(iso_time: str) -> str:
    event_time = datetime.fromisoformat(iso_time)
    if event_time.tzinfo is None:
        event_time = event_time.replace(tzinfo=ZoneInfo(settings.timezone))
    return event_time.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _escape_ics_text(value: str) -> str:
    return (value.replace("\\", "\\\\")
                 .replace("\r\n", "\n").replace("\r", "\n")
                 .replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;"))


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
            "PRODID:-//Sailor-Moon//Activity Assistant//CN",
            "BEGIN:VEVENT",
            f"UID:{uuid.uuid4().hex}@sailor-moon",
            f"DTSTAMP:{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
            f"DTSTART:{_to_ics_time(event_time)}",
            f"SUMMARY:{_escape_ics_text(title)}",
            f"DESCRIPTION:{_escape_ics_text(description)}",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ])
        return {"title": title, "event_time": event_time, "ics": ics}
