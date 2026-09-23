from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


class TaskItem(BaseModel):
    title: str
    assignee: str


class FormField(BaseModel):
    name: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_]{0,30}$")
    label: str
    type: Literal["text", "select"] = "text"
    required: bool = False
    options: list[str] = Field(default_factory=list)

    @field_validator("type", mode="before")
    @classmethod
    def normalize_type(cls, value):
        return value if value in {"text", "select"} else "text"


class ToolIntent(BaseModel):
    tool: Literal["create_form", "assign_tasks", "create_calendar", "schedule_reminder", "publish_message"]
    arguments: dict[str, Any] = Field(default_factory=dict)


class ActivityPlan(BaseModel):
    title: str
    description: str = ""
    event_time: str = ""
    materials: list[str] = Field(default_factory=list)
    tasks: list[TaskItem] = Field(default_factory=list)
    form_fields: list[FormField] = Field(default_factory=list)
    remind_minutes_before: int = 60
    tool_intents: list[ToolIntent] = Field(default_factory=list)


class WorkflowConfig(BaseModel):
    create_plan: bool = True
    create_form: bool = False
    assign_tasks: bool = False
    create_calendar: bool = False
    schedule_reminder: bool = False
    publish_message: bool = True
    collect_submission: bool = False


class WorkDraft(BaseModel):
    intent_type: Literal["assignment", "activity", "survey", "notice"]
    complexity: Literal["simple", "standard", "complex"]
    title: str
    summary: str
    description: str = ""
    deadline: str = ""
    event_time: str = ""
    reasoning: str = ""
    missing_information: list[str] = Field(default_factory=list)
    form_fields: list[FormField] = Field(default_factory=list)
    workflow: WorkflowConfig

    @field_validator("deadline", "event_time", mode="before")
    @classmethod
    def normalize_optional_times(cls, value):
        return value or ""


class PlanRequest(BaseModel):
    raw_input: str
    available_assignees: list[str] = Field(default_factory=list)
    organization_id: str = ""


class WorkRequest(BaseModel):
    raw_input: str
    class_context: str = ""
    organization_id: str = ""


class RecapRequest(BaseModel):
    activity_title: str
    stats: dict[str, Any]
    task_summary: str
    organization_id: str = ""


class ReplyRequest(BaseModel):
    message: str
    activity_context: str = ""
    organization_id: str = ""


class EmbedRequest(BaseModel):
    text: str = Field(min_length=1, max_length=100_000)


class RagSearchRequest(BaseModel):
    organization_id: str | None = None
    query: str
    top_k: int = Field(default=5, ge=1, le=20)
