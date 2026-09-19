from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class ActivityStatus(str, Enum):
    PLANNED = "planned"
    RUNNING = "running"
    FINISHED = "finished"
    CANCELLED = "cancelled"


class ReminderStatus(str, Enum):
    SCHEDULED = "scheduled"
    SENT = "sent"
    CANCELLED = "cancelled"


class TaskStatus(str, Enum):
    PENDING = "pending"
    DONE = "done"


class TaskUpdate(BaseModel):
    status: TaskStatus


class TaskItem(BaseModel):
    title: str
    assignee: str


class FormField(BaseModel):
    name: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_]{0,30}$")
    label: str
    type: Literal["text", "select"] = "text"
    required: bool = False
    options: list[str] = Field(default_factory=list)


class ActivityPlan(BaseModel):
    """LLM 生成的活动策划。"""

    title: str
    description: str = ""
    event_time: str = ""                 # ISO 格式活动时间
    materials: list[str] = Field(default_factory=list)
    tasks: list[TaskItem] = Field(default_factory=list)
    form_fields: list[FormField] = Field(default_factory=list)
    remind_minutes_before: int = 60      # 活动前多久发提醒


class ActivityCreate(BaseModel):
    user_id: str = ""
    text: str                            # 一句话活动需求
    publish_to_qq: bool = True
    class_id: str | None = None


class RegistrationIn(BaseModel):
    name: str
    contact: str
    extra: dict = Field(default_factory=dict)


class ActivityMessageIn(BaseModel):
    content: str = Field(min_length=1, max_length=4000)
    request_id: str = Field(min_length=8, max_length=80)


class QQInboundIn(BaseModel):
    event_type: str = "GROUP_MESSAGE_CREATE"
    group_openid: str
    sender_openid: str = ""
    sender_name: str = ""
    content: str
    message_id: str
    timestamp: str = ""


class BootstrapIn(BaseModel):
    username: str = Field(min_length=3, max_length=40)
    display_name: str = Field(min_length=1, max_length=40)
    password: str = Field(min_length=8, max_length=128)
    organization_name: str = Field(default="我的组织", min_length=1, max_length=80)
    accept_privacy: bool


class LoginIn(BaseModel):
    username: str
    password: str


class PasswordChangeIn(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8, max_length=128)


class ParticipantPasswordResetIn(BaseModel):
    new_password: str = Field(min_length=8, max_length=128)


class PrivacyConsentIn(BaseModel):
    accept: bool


class ClassCreateIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class RosterMemberIn(BaseModel):
    username: str = Field(min_length=3, max_length=40)
    display_name: str = Field(min_length=1, max_length=40)
    student_no: str = Field(default="", max_length=40)
    password: str = Field(min_length=6, max_length=128)


class RosterImportIn(BaseModel):
    members: list[RosterMemberIn]


class TodoCreateIn(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=4000)
    deadline: str
    kind: str = "homework"


class TodoReminderIn(BaseModel):
    remind_at: str


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


class AgentRequestIn(BaseModel):
    class_id: str
    text: str = Field(min_length=2, max_length=4000)


class AgentDraftUpdateIn(BaseModel):
    draft: WorkDraft
