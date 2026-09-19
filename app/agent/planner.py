from ..models.schemas import ActivityPlan
from .llm import LLMClient

STEPS = ["generate_plan", "create_form", "assign_tasks", "create_calendar_event", "schedule_reminder"]


def plan_activity(llm: LLMClient, raw_input: str, available_assignees: list[str] | None = None) -> ActivityPlan:
    """规划阶段：把一句话需求变成结构化活动策划。"""
    return llm.generate_plan(raw_input, available_assignees)
