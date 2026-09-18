from enum import Enum

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


class ActivityPlan(BaseModel):
    """LLM 生成的活动策划。"""

    title: str
    description: str = ""
    event_time: str = ""                 # ISO 格式活动时间
    materials: list[str] = Field(default_factory=list)
    tasks: list[TaskItem] = Field(default_factory=list)
    remind_minutes_before: int = 60      # 活动前多久发提醒


class ActivityCreate(BaseModel):
    user_id: str
    text: str                            # 一句话活动需求


class RegistrationIn(BaseModel):
    name: str
    contact: str
    extra: dict = Field(default_factory=dict)
