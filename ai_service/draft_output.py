import re
from unicodedata import normalize

from .draft_time import model_time


FORM_GUIDANCE = (
    "问卷题目用 name、label、type、required、options。name 必须唯一且为字母开头的英文标识，"
    "type 只用 text 或 select，选择题必须给出选项数组，required 用 JSON 布尔值。"
)
_TASK_REQUEST = re.compile(
    r"分工|分配(?:具体)?任务|明确职责|(?:指派|分配|指定|安排)(?:\s*[^，。；,;、]{0,10})"
    r"(?:负责人|主持|采购|签到|布置|宣传)|(?:负责|分给)(?:主持|采购|签到|布置|宣传|场地)"
)
_NEGATED_STEPS = {
    "create_form": r"(?:创建|收集|开放)?报名(?:表)?",
    "assign_tasks": r"(?:分工|分配任务)",
    "create_calendar": r"(?:创建|写入|添加)?日历",
    "schedule_reminder": r"(?:定时)?提醒",
    "publish_message": r"(?:在\s*QQ\s*群(?:里)?发(?:布|送)?(?:消息|通知)?|发(?:到|送到)?\s*(?:QQ\s*)?群(?:里|消息|通知)?|群发|发\s*QQ\s*(?:消息|通知)?)",
}
_DURATION = re.compile(r"(?P<number>[0-9]{1,6}|半|一|两|二|三|四|五|六|七|八|九|十)\s*(?:个)?\s*(?P<unit>分钟|小时|天)")
_REMINDER = re.compile(r"提前\s*" + _DURATION.pattern + r"\s*(?:(?:在)?站内|在\s*(?:QQ\s*)?群里?|发消息)?\s*提醒", re.IGNORECASE)
_UNIT_MINUTES = {"分钟": 1, "小时": 60, "天": 1440}
_SMALL_NUMBERS = {word: index for index, word in enumerate("零一二三四五六七八九十")} | {"两": 2, "半": 0.5}
_PRIORITY = {
    "低": "low", "低优先级": "low", "普通": "normal", "中": "normal", "medium": "normal",
    "高": "high", "高优先级": "high", "紧急": "urgent",
}


def boolean(value: object, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (str, int)):
        text = str(value).strip().lower()
        if text in {"true", "1", "yes", "是", "必填"}:
            return True
        if text in {"false", "0", "no", "否", "选填", "非必填"}:
            return False
    return default


def _text_list(value: object) -> list[str]:
    items = re.split(r"[\n,，、]+", value) if isinstance(value, str) else value
    if not isinstance(items, list):
        return []
    return list(dict.fromkeys(item.strip() for item in items if isinstance(item, str) and item.strip()))


def form_fields(raw: object) -> list[dict]:
    if not isinstance(raw, list):
        return []
    result = []
    names = set()
    for index, item in enumerate(raw[:100], start=1):
        field = item if isinstance(item, dict) else {"label": item}
        name = field.get("name")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,30}", name) or name in names:
            suffix = index
            name = f"field_{suffix}"
            while name in names:
                suffix += 1
                name = f"field_{suffix}"
        names.add(name)
        label = field.get("label")
        options = [item for item in _text_list(field.get("options")) if len(item) <= 1000][:100]
        kind = str(field.get("type", "")).strip().lower()
        select = bool(options) and kind in {"select", "radio", "choice", "single_choice", "单选", "单选题"}
        result.append({
            "name": name,
            "label": (label.strip() if isinstance(label, str) else "")[:255] or f"问题 {index}",
            "type": "select" if select else "text",
            "required": boolean(field.get("required")),
            "options": options if select else [],
        })
    return result


def apply_step_preferences(workflow: dict, raw_input: str) -> dict:
    text = normalize("NFKC", raw_input)
    for step, phrase in _NEGATED_STEPS.items():
        if re.search(rf"(?:不用|无需|不需要|不要|不必|别|不)\s*(?:(?:再|自动|额外)\s*)*(?:{phrase})(?=[，。；,;、！!\s]|$)", text):
            workflow[step] = False
    if re.search(r"(?:仅|只)(?:在)?站内(?:发布|通知|提醒)?(?=[，。；,;、！!\s]|$)", text):
        workflow["publish_message"] = False
    return workflow


def requests_task_assignment(raw_input: str) -> bool:
    text = normalize("NFKC", raw_input)
    return bool(_TASK_REQUEST.search(text)) and apply_step_preferences({"assign_tasks": True}, text)["assign_tasks"]


def reminder_minutes(raw_input: str, generated: object = None) -> int:
    requests = list(_REMINDER.finditer(normalize("NFKC", raw_input)))
    if len(requests) == 1:
        duration = requests[0]
    elif isinstance(generated, str):
        duration = _DURATION.fullmatch(normalize("NFKC", generated).strip())
    else:
        duration = None
    if duration:
        amount, unit = duration.group("number", "unit")
        minutes = int((int(amount) if amount.isdecimal() else _SMALL_NUMBERS[amount]) * _UNIT_MINUTES[unit])
        if 0 <= minutes <= 525600:
            return minutes
    if isinstance(generated, str) and re.fullmatch(r"[0-9]{1,6}", generated.strip()):
        generated = int(generated)
    return generated if type(generated) is int and 0 <= generated <= 525600 else 60


def normalize_task(task: dict) -> None:
    for field in ("description", "key", "account_id", "assignee", "unassigned_reason"):
        if task.get(field) is None:
            task[field] = ""
    priority = str(task.get("priority", "")).strip().lower()
    task["priority"] = _PRIORITY.get(priority, priority if priority in {"low", "normal", "high", "urgent"} else "normal")
    task["depends_on"] = _text_list(task.get("depends_on"))
    task["deadline"] = model_time(task.get("deadline"))
