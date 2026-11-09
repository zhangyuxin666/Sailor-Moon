import re
from datetime import datetime, timedelta
from unicodedata import normalize
from zoneinfo import ZoneInfo


SHANGHAI = ZoneInfo("Asia/Shanghai")
TIME_GUIDANCE = (
    "时间字段只填写单个 ISO 8601 日期时间（例如 2026-11-13T10:00:00+08:00）或空字符串。"
    "以当前上海时间解析相对日期，周一是一周的开始。活动时间段用开始时间作为 event_time，"
    "结束时间保留在说明里。日期或时刻无法确定时留空，任务截止时间也遵守此格式。"
)
_ISO_DATETIME = re.compile(
    r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:[0-5]\d)?"
)
_DAY = (
    r"\d{4}-\d{2}-\d{2}|(?:\d{4}年)?\d{1,2}月\d{1,2}[日号]"
    r"|(?<![本这下上])(?:本|这|下)?(?:周|星期)[一二三四五六日天]|今天|明天|后天"
)
_NUMBER = r"\d{1,2}|[零一二两三四五六七八九十]{1,3}"
_CLOCK = (
    r"(?P<period>凌晨|早上|上午|中午|下午|晚上)?\s*"
    rf"(?P<hour>{_NUMBER})"
    rf"(?::(?P<minute>\d{{2}})(?![\d:])|[点时](?:(?P<half>半)|(?P<spoken_minute>{_NUMBER})分|整)?)"
)
_REQUEST_TIME = re.compile(
    rf"(?P<day>{_DAY})\s*的?\s*{_CLOCK}"
)
_CLOCK_PATTERN = re.compile(_CLOCK)
_RANGE_SEPARATOR = re.compile(r"\s*[-–—~至到]\s*")
_EXPLICIT_TIME_ZONE = re.compile(r"(?<![A-Z])(?:UTC|GMT)(?![A-Z])|(?:当地|纽约|伦敦|东京|美东|美西)时间", re.IGNORECASE)


def _clock_number(value: str) -> int:
    if value.isascii() and value.isdigit():
        return int(value)
    digits = "零一二三四五六七八九"
    value = value.replace("两", "二")
    if "十" in value:
        tens, ones = value.split("十")
        return 10 * (digits.index(tens) if tens else 1) + (digits.index(ones) if ones else 0)
    return digits.index(value)


def has_alternative_times(text: str) -> bool:
    text = normalize("NFKC", text)
    clocks = list(_CLOCK_PATTERN.finditer(text))
    return any(
        re.fullmatch(rf"\s*(?:或|或者|还是)\s*(?:{_DAY})?\s*", text[before.end():after.start()])
        for before, after in zip(clocks, clocks[1:])
    )


def model_time(value: object) -> str:
    if not isinstance(value, str):
        return ""
    # An ISO interval may use a slash between two full timestamps.
    start = normalize("NFKC", value).strip().split("/", 1)[0].strip()
    if not _ISO_DATETIME.fullmatch(start):
        return ""
    try:
        parsed = datetime.fromisoformat(start)
    except ValueError:
        return ""
    offset = parsed.utcoffset()
    if offset is not None and abs(offset) > timedelta(hours=18):
        return ""
    return parsed.isoformat()


def resolved_draft_time(text: str, now: datetime, generated: object) -> str:
    if has_alternative_times(text):
        return ""
    return request_time(text, now) or model_time(generated)


def request_time(text: str, now: datetime) -> str:
    """Resolve an explicit date and clock in the request when there is only one candidate."""
    text = normalize("NFKC", text)
    if _EXPLICIT_TIME_ZONE.search(text):
        return ""
    match = _REQUEST_TIME.search(text)
    if not match or len(re.findall(_DAY, text)) != 1:
        return ""
    if _CLOCK_PATTERN.search(text, 0, match.start()):
        return ""
    later_clocks = list(_CLOCK_PATTERN.finditer(text, match.end()))
    if later_clocks and (
        len(later_clocks) != 1 or not _RANGE_SEPARATOR.fullmatch(text[match.end():later_clocks[0].start()])
    ):
        return ""
    day, period, hour, minute = match.group("day", "period", "hour", "minute")
    try:
        if "月" in day:
            parts = re.fullmatch(r"(?:(\d{4})年)?(\d{1,2})月(\d{1,2})[日号]", day)
            year, month, day_of_month = parts.groups()
            date = datetime(int(year or now.year), int(month), int(day_of_month)).date()
            if not year and date < now.date():
                return ""
        elif day[0].isdigit():
            date = datetime.fromisoformat(day).date()
        elif day in {"今天", "明天", "后天"}:
            date = now.date() + timedelta(days={"今天": 0, "明天": 1, "后天": 2}[day])
        else:
            weekday = "一二三四五六日天".index(day[-1])
            days = min(weekday, 6) - now.weekday()
            if day[0] == "下" or (day[0] in "周星" and days < 0):
                days += 7
            date = now.date() + timedelta(days=days)
        clock_hour = _clock_number(hour)
        if period and (clock_hour > 12 or (clock_hour == 12 and period == "晚上")):
            return ""
        if period in {"中午", "下午", "晚上"} and 0 < clock_hour < 12:
            clock_hour += 12
        elif period == "凌晨" and clock_hour == 12:
            clock_hour = 0
        clock_minute = 30 if match["half"] else _clock_number(minute or match["spoken_minute"] or "0")
        return datetime(date.year, date.month, date.day, clock_hour, clock_minute, tzinfo=SHANGHAI).isoformat()
    except ValueError:
        return ""
