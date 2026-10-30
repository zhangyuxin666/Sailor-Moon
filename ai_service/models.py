from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class AssigneeCandidate(BaseModel):
    account_id: str = Field(min_length=1, max_length=32)
    display_name: str = Field(min_length=1)


class TaskItem(BaseModel):
    title: str
    description: str = ""
    deadline: str = ""
    priority: Literal["low", "normal", "high", "urgent"] = "normal"
    key: str = ""
    depends_on: list[str] = Field(default_factory=list)
    account_id: str = ""
    assignee: str = ""
    unassigned_reason: str = ""


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


class GenerationInfo(BaseModel):
    mode: Literal["model", "offline"] = "offline"
    fallback_reason: str = "not_configured"
    retrieval_status: Literal["used", "empty", "unavailable"] = "empty"
    embedding_profile: str = ""
    sources: list[dict[str, Any]] = Field(default_factory=list)
    pending_fields: list[str] = Field(default_factory=list)


class ActivityPlan(BaseModel):
    generation: GenerationInfo = Field(default_factory=GenerationInfo)
    requires_event_time: bool = True
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
    generation: GenerationInfo = Field(default_factory=GenerationInfo)
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
    available_assignees: list[AssigneeCandidate] = Field(default_factory=list)
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
    
class ChunkRequest(BaseModel):
    """文档切分接口接收的请求数据。"""

    # 需要切分的完整文档，不能为空，最多允许十万个字符
    content: str = Field(min_length=1, max_length=100_000)

    # 每个片段的目标最大长度
    chunk_size: int = Field(default=500, ge=50, le=2_000)

    # 相邻片段之间重复保留的字符数
    overlap: int = Field(default=80, ge=0, le=500)

    @model_validator(mode="after")
    def validate_overlap(self):
        """重叠长度必须小于片段长度。"""

        if self.overlap >= self.chunk_size:
            raise ValueError("overlap 必须小于 chunk_size")

        return self

class RagSearchRequest(BaseModel):
    organization_id: str | None = None
    query: str
    top_k: int = Field(default=5, ge=1, le=20)
