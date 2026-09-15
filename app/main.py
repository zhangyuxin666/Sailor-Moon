import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .agent.llm import get_llm
from .config import settings
from .core.exceptions import NotFoundError, PermissionDeniedError
from .models.database import Database
from .models.schemas import ActivityCreate, RegistrationIn
from .scheduler.reminders import ReminderService
from .services.activity_service import ActivityService

logging.basicConfig(level=logging.INFO)

db = Database(settings.database_path)
reminders = ReminderService(db)
service = ActivityService(db, get_llm(), reminders)


@asynccontextmanager
async def lifespan(app: FastAPI):
    reminders.start()  # 启动调度器并从 DB 重建未触发的提醒
    yield
    reminders.shutdown()


app = FastAPI(title="活动管家", description="一句话发起活动，全流程自动跑", lifespan=lifespan)


@app.exception_handler(PermissionDeniedError)
async def permission_denied_handler(request: Request, exc: PermissionDeniedError):
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@app.exception_handler(NotFoundError)
async def not_found_handler(request: Request, exc: NotFoundError):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.post("/activities")
def create_activity(body: ActivityCreate):
    """一句话发起活动：自动完成策划、建问卷、派任务、排提醒。"""
    return service.create_activity_from_text(body.user_id, body.text)


@app.get("/activities/{activity_id}")
def get_activity(activity_id: str, user_id: str):
    return service.get_activity(user_id, activity_id)


@app.post("/forms/{form_id}/registrations")
def submit_registration(form_id: str, body: RegistrationIn):
    return service.submit_registration(form_id, body.name, body.contact, body.extra)


@app.get("/activities/{activity_id}/form-stats")
def form_stats(activity_id: str, user_id: str):
    return service.form_stats(user_id, activity_id)


@app.post("/activities/{activity_id}/recap")
def recap(activity_id: str, user_id: str):
    return service.generate_recap(user_id, activity_id)


@app.post("/reminders/{reminder_id}/cancel")
def cancel_reminder(reminder_id: str, user_id: str):
    return service.cancel_reminder(user_id, reminder_id)
