import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse, Response

from .agent.llm import get_llm
from .config import settings
from .core.exceptions import NotFoundError, PermissionDeniedError
from .core.permissions import check_activity_owner
from .models.database import Database
from .models.schemas import ActivityCreate, RegistrationIn, TaskUpdate
from .jobs import JobQueue
from .scheduler.reminders import ReminderService
from .services.activity_service import ActivityService

logging.basicConfig(level=logging.INFO)

db = Database(settings.database_url)
queue = JobQueue(db)
reminders = ReminderService(db, queue=queue)
service = ActivityService(db, get_llm(), reminders, queue=queue)


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield


app = FastAPI(title="活动管家", description="一句话发起活动，全流程自动跑", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.exception_handler(PermissionDeniedError)
async def permission_denied_handler(request: Request, exc: PermissionDeniedError):
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@app.exception_handler(NotFoundError)
async def not_found_handler(request: Request, exc: NotFoundError):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.post("/activities", status_code=status.HTTP_202_ACCEPTED)
def create_activity(body: ActivityCreate):
    """活动落库后立即返回；Worker 在后台执行完整流程。"""
    return service.queue_activity(body.user_id, body.text)


@app.get("/runs/{run_id}")
def get_run(run_id: str, user_id: str):
    run = queue.get(run_id)
    if not run:
        raise NotFoundError(f"执行记录不存在: {run_id}")
    with db.connect() as conn:
        if run["kind"] == "activity":
            check_activity_owner(conn, run["ref_id"], user_id)
        else:
            reminder = conn.execute(
                "SELECT activity_id FROM reminders WHERE id = ?", (run["ref_id"],)
            ).fetchone()
            if not reminder:
                raise NotFoundError(f"提醒不存在: {run['ref_id']}")
            check_activity_owner(conn, reminder["activity_id"], user_id)
    return run


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


@app.patch("/tasks/{task_id}")
def update_task(task_id: str, user_id: str, body: TaskUpdate):
    return service.update_task(user_id, task_id, body.status.value)


@app.get("/activities/{activity_id}/calendar.ics")
def download_calendar(activity_id: str, user_id: str):
    return Response(
        content=service.get_calendar_ics(user_id, activity_id),
        media_type="text/calendar; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="activity.ics"'},
    )
