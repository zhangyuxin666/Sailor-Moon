from app.tools.calendar_tool import _escape_ics_text, _to_ics_time


def test_calendar_time_uses_configured_timezone():
    assert _to_ics_time("2026-09-18T19:00:00") == "20260918T110000Z"
    assert _to_ics_time("2026-09-18T19:00:00+08:00") == "20260918T110000Z"


def test_calendar_text_is_escaped():
    assert _escape_ics_text("标题,甲;乙\n下一行\\") == "标题\\,甲\\;乙\\n下一行\\\\"
