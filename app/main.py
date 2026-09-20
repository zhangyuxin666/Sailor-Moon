import logging
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, Request, UploadFile, status
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles

from .agent.llm import get_llm
from .config import settings
from .core.exceptions import NotFoundError, PermissionDeniedError
from .core.permissions import check_activity_owner
from .models.database import Database
from .models.schemas import (
    ActivityCreate, ActivityMessageIn, BootstrapIn, ClassCreateIn, LoginIn,
    AgentDraftUpdateIn, AgentRequestIn, ParticipantPasswordResetIn, PasswordChangeIn, PrivacyConsentIn, QQInboundIn,
    RegistrationIn, RosterImportIn, TaskUpdate, TodoCreateIn, TodoReminderIn,
)
from .integrations.qq import QQBotService
from .integrations.qq_handler import process_group_message
from .core.auth import (
    SESSION_COOKIE, create_account, create_session, get_current_account,
    set_password, verify_password,
)
from .core.audit import write_audit
from .services.classroom_service import ClassroomService
from .services.storage_service import StorageService
from .services.agent_draft_service import AgentDraftService
from .jobs import JobQueue
from .scheduler.reminders import ReminderService
from .services.activity_service import ActivityService

logging.basicConfig(level=logging.INFO)

db = Database(settings.database_url)
queue = JobQueue(db)
reminders = ReminderService(db, queue=queue)
service = ActivityService(db, get_llm(), reminders, queue=queue)
qq = QQBotService(db)
classroom = ClassroomService(db, reminders=reminders)
storage = StorageService()
drafts = AgentDraftService(db, service.llm, classroom, service)
login_attempts = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield


app = FastAPI(title="活动管家", description="一句话发起活动，全流程自动跑", lifespan=lifespan)
static_dir = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=static_dir), name="static")


def app_page(filename: str):
    # Authenticated and unauthenticated pages can share the same URL (notably
    # "/"). Never let the browser reuse an HTML response from another session.
    return FileResponse(
        static_dir / filename,
        headers={
            "Cache-Control": "no-store, max-age=0",
            "Pragma": "no-cache",
            "Vary": "Cookie",
        },
    )


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    if request.method in {"POST", "PUT", "PATCH", "DELETE"} and request.cookies.get(SESSION_COOKIE):
        origin = request.headers.get("origin")
        if origin:
            allowed = {
                settings.public_base_url.rstrip("/"),
                "http://127.0.0.1:8001",
                "http://localhost:8001",
            }
            if origin.rstrip("/") not in allowed:
                return JSONResponse(status_code=403, content={"detail": "请求来源不受信任"})
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "same-origin"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data:; connect-src 'self'"
    )
    return response


@app.get("/", include_in_schema=False)
def web_app(request: Request):
    account = get_current_account(db, request, required=False)
    destination = "/portal" if account else "/login"
    return RedirectResponse(destination, headers={"Cache-Control": "no-store"})


@app.get("/login", include_in_schema=False)
def login_page(request: Request):
    if get_current_account(db, request, required=False):
        return RedirectResponse("/portal", headers={"Cache-Control": "no-store"})
    return app_page("login.html")


@app.get("/portal", include_in_schema=False)
def portal(request: Request):
    if not get_current_account(db, request, required=False):
        return RedirectResponse("/login", headers={"Cache-Control": "no-store"})
    return app_page("dashboard.html")


def protected_page(request: Request, filename: str):
    if not get_current_account(db, request, required=False):
        return RedirectResponse("/login", headers={"Cache-Control": "no-store"})
    return app_page(filename)


@app.get("/agent", include_in_schema=False)
def agent_page(request: Request):
    return protected_page(request, "agent.html")


@app.get("/assignments", include_in_schema=False)
def assignments_page(request: Request):
    return protected_page(request, "assignments.html")


@app.get("/members", include_in_schema=False)
def members_page(request: Request):
    return protected_page(request, "members.html")


@app.get("/settings", include_in_schema=False)
def settings_page(request: Request):
    return protected_page(request, "settings.html")


@app.get("/activity", include_in_schema=False)
def activity_workspace(request: Request):
    if not get_current_account(db, request, required=False) and not settings.allow_legacy_api:
        return RedirectResponse("/login", headers={"Cache-Control": "no-store"})
    return app_page("index.html")


@app.get("/privacy", include_in_schema=False)
def privacy_page():
    return FileResponse(static_dir / "privacy.html")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.exception_handler(PermissionDeniedError)
async def permission_denied_handler(request: Request, exc: PermissionDeniedError):
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@app.exception_handler(NotFoundError)
async def not_found_handler(request: Request, exc: NotFoundError):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.exception_handler(ValueError)
async def value_error_handler(request: Request, exc: ValueError):
    return JSONResponse(status_code=400, content={"detail": str(exc)})


@app.get("/auth/status")
def auth_status(request: Request):
    with db.connect() as conn:
        initialized = bool(conn.execute("SELECT 1 FROM accounts LIMIT 1").fetchone())
    account = get_current_account(db, request, required=False)
    if account:
        account.pop("password_hash", None)
        with db.connect() as conn:
            consent = conn.execute(
                "SELECT 1 FROM privacy_consents WHERE account_id = ? AND policy_version = '1.0'",
                (account["id"],),
            ).fetchone()
        account["privacy_accepted"] = bool(consent)
    return {"initialized": initialized, "account": account}


@app.post("/auth/bootstrap")
def bootstrap(body: BootstrapIn):
    if not body.accept_privacy:
        raise ValueError("必须阅读并同意隐私政策与用户协议")
    with db.connect() as conn:
        if conn.execute("SELECT 1 FROM accounts LIMIT 1").fetchone():
            raise PermissionDeniedError("系统已经完成初始化")
        account = create_account(
            conn, body.username, body.display_name, body.password, "manager"
        )
        now = __import__("datetime").datetime.now().isoformat()
        conn.execute(
            "INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) "
            "VALUES (?, 0, 0, ?)",
            (account["id"], now),
        )
        organization_id = uuid.uuid4().hex[:12]
        conn.execute(
            "INSERT INTO organizations (id, name, owner_account_id, created_at) VALUES (?, ?, ?, ?)",
            (organization_id, body.organization_name, account["id"], now),
        )
        conn.execute(
            "INSERT INTO organization_members (organization_id, account_id, role, created_at) "
            "VALUES (?, ?, 'owner', ?)",
            (organization_id, account["id"], now),
        )
        conn.execute(
            "INSERT INTO privacy_consents (account_id, policy_version, accepted_at) VALUES (?, '1.0', ?)",
            (account["id"], now),
        )
        write_audit(
            conn, "account.bootstrap", account["id"], organization_id,
            "account", account["id"], {"username": body.username},
        )
        token = create_session(conn, account["id"])
    response = JSONResponse(account)
    response.set_cookie(
        SESSION_COOKIE, token, httponly=True, samesite="strict",
        secure=settings.cookie_secure, max_age=settings.session_days * 86400,
    )
    return response


@app.post("/auth/login")
def login(body: LoginIn, request: Request):
    remote = request.client.host if request.client else "unknown"
    attempt_key = f"{remote}:{body.username}"
    history = [stamp for stamp in login_attempts.get(attempt_key, []) if time.time() - stamp < 300]
    if len(history) >= 5:
        return JSONResponse(status_code=429, content={"detail": "登录失败次数过多，请 5 分钟后再试"})
    with db.connect() as conn:
        row = conn.execute(
            "SELECT a.*, sec.force_password_change, sec.locked_until FROM accounts a "
            "LEFT JOIN account_security sec ON sec.account_id = a.id "
            "WHERE a.username = ? AND a.status = 'active'", (body.username,)
        ).fetchone()
        if not row or not verify_password(body.password, row["password_hash"]):
            history.append(time.time())
            login_attempts[attempt_key] = history
            return JSONResponse(status_code=401, content={"detail": "用户名或密码错误"})
        login_attempts.pop(attempt_key, None)
        token = create_session(conn, row["id"])
        account = {key: value for key, value in dict(row).items() if key != "password_hash"}
    response = JSONResponse(account)
    response.set_cookie(
        SESSION_COOKIE, token, httponly=True, samesite="strict",
        secure=settings.cookie_secure, max_age=settings.session_days * 86400,
    )
    return response


@app.post("/auth/change-password")
def change_password(request: Request, body: PasswordChangeIn):
    account = get_current_account(db, request)
    with db.connect() as conn:
        row = conn.execute("SELECT password_hash FROM accounts WHERE id = ?", (account["id"],)).fetchone()
        if not verify_password(body.current_password, row["password_hash"]):
            return JSONResponse(status_code=400, content={"detail": "当前密码错误"})
        set_password(conn, account["id"], body.new_password)
        write_audit(conn, "account.password_change", account["id"], resource_type="account", resource_id=account["id"])
    return {"status": "ok"}


@app.post("/accounts/{account_id}/reset-password")
def reset_participant_password(account_id: str, request: Request, body: ParticipantPasswordResetIn):
    manager = get_current_account(db, request)
    if manager["role"] != "manager":
        raise PermissionDeniedError("只有管理者可以重置成员密码")
    with db.connect() as conn:
        target = conn.execute(
            "SELECT a.role, om.organization_id FROM accounts a "
            "JOIN organization_members om ON om.account_id = a.id WHERE a.id = ?",
            (account_id,),
        ).fetchone()
        manager_org = conn.execute(
            "SELECT organization_id FROM organization_members WHERE account_id = ? AND role IN ('owner', 'manager') LIMIT 1",
            (manager["id"],),
        ).fetchone()
        if not target or target["role"] != "participant" or not manager_org or target["organization_id"] != manager_org["organization_id"]:
            raise PermissionDeniedError("无权重置该账号密码")
        set_password(conn, account_id, body.new_password, force_change=True)
        write_audit(
            conn, "account.password_reset", manager["id"], manager_org["organization_id"],
            "account", account_id,
        )
    return {"status": "ok", "force_password_change": True}


@app.post("/privacy/consent")
def accept_privacy(request: Request, body: PrivacyConsentIn):
    if not body.accept:
        raise ValueError("必须同意隐私政策后才能继续使用")
    account = get_current_account(db, request)
    now = __import__("datetime").datetime.now().isoformat()
    with db.connect() as conn:
        if conn.execute("SELECT 1 FROM privacy_consents WHERE account_id = ?", (account["id"],)).fetchone():
            conn.execute(
                "UPDATE privacy_consents SET policy_version = '1.0', accepted_at = ? WHERE account_id = ?",
                (now, account["id"]),
            )
        else:
            conn.execute(
                "INSERT INTO privacy_consents (account_id, policy_version, accepted_at, ip_address) VALUES (?, '1.0', ?, ?)",
                (account["id"], now, request.client.host if request.client else None),
            )
    return {"status": "ok", "policy_version": "1.0"}


@app.get("/audit-logs")
def list_audit_logs(request: Request, limit: int = 100):
    account = get_current_account(db, request)
    if account["role"] != "manager":
        raise PermissionDeniedError("只有管理者可以查看审计日志")
    with db.connect() as conn:
        organization = conn.execute(
            "SELECT organization_id FROM organization_members WHERE account_id = ? LIMIT 1",
            (account["id"],),
        ).fetchone()
        rows = conn.execute(
            "SELECT * FROM audit_logs WHERE organization_id = ? ORDER BY created_at DESC LIMIT ?",
            (organization["organization_id"], min(max(limit, 1), 500)),
        ).fetchall()
    return {"logs": [dict(row) for row in rows]}


@app.get("/account/export")
def export_account_data(request: Request):
    account = get_current_account(db, request)
    with db.connect() as conn:
        memberships = [dict(row) for row in conn.execute(
            "SELECT c.id, c.name FROM class_members cm JOIN classes c ON c.id = cm.class_id "
            "WHERE cm.account_id = ?",
            (account["id"],),
        ).fetchall()]
        submissions = [dict(row) for row in conn.execute(
            "SELECT t.title, ta.status, ta.note, ta.original_filename, ta.submitted_at "
            "FROM todo_assignees ta JOIN todos t ON t.id = ta.todo_id WHERE ta.account_id = ?",
            (account["id"],),
        ).fetchall()]
        consents = [dict(row) for row in conn.execute(
            "SELECT policy_version, accepted_at FROM privacy_consents WHERE account_id = ?",
            (account["id"],),
        ).fetchall()]
    safe_account = {
        key: value for key, value in account.items()
        if key not in {"password_hash", "force_password_change"}
    }
    payload = {
        "exported_at": __import__("datetime").datetime.now().isoformat(),
        "account": safe_account,
        "classes": memberships,
        "submissions": submissions,
        "privacy_consents": consents,
    }
    import json

    return Response(
        content=json.dumps(payload, ensure_ascii=False, indent=2),
        media_type="application/json; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="my-data.json"'},
    )


@app.delete("/account")
def delete_account(request: Request):
    account = get_current_account(db, request)
    if account["role"] == "manager":
        raise ValueError("管理者需要先转移或删除名下组织，暂不能直接注销")
    with db.connect() as conn:
        assets = conn.execute(
            "SELECT storage_key FROM file_assets WHERE account_id = ?", (account["id"],)
        ).fetchall()
    for asset in assets:
        storage.delete(asset["storage_key"])
    now = __import__("datetime").datetime.now().isoformat()
    with db.connect() as conn:
        organizations = conn.execute(
            "SELECT organization_id FROM organization_members WHERE account_id = ?",
            (account["id"],),
        ).fetchall()
        for organization in organizations:
            write_audit(
                conn, "account.delete", account["id"], organization["organization_id"],
                "account", account["id"],
            )
        conn.execute("DELETE FROM auth_sessions WHERE account_id = ?", (account["id"],))
        conn.execute("DELETE FROM privacy_consents WHERE account_id = ?", (account["id"],))
        conn.execute("DELETE FROM class_members WHERE account_id = ?", (account["id"],))
        conn.execute("DELETE FROM todo_assignees WHERE account_id = ?", (account["id"],))
        conn.execute("DELETE FROM file_assets WHERE account_id = ?", (account["id"],))
        conn.execute("DELETE FROM organization_members WHERE account_id = ?", (account["id"],))
        conn.execute(
            "UPDATE accounts SET username = ?, display_name = '已注销用户', student_no = NULL, "
            "password_hash = ?, status = 'deleted' WHERE id = ?",
            (f"deleted-{uuid.uuid4().hex}", secrets.token_hex(32), account["id"]),
        )
    response = JSONResponse({"status": "deleted", "deleted_at": now})
    response.delete_cookie(SESSION_COOKIE)
    return response


@app.post("/auth/logout")
def logout(request: Request):
    import hashlib

    token = request.cookies.get(SESSION_COOKIE, "")
    if token:
        with db.connect() as conn:
            conn.execute(
                "DELETE FROM auth_sessions WHERE token_hash = ?",
                (hashlib.sha256(token.encode()).hexdigest(),),
            )
    response = JSONResponse({"status": "ok"})
    response.delete_cookie(SESSION_COOKIE)
    return response


@app.get("/portal/dashboard")
def portal_dashboard(request: Request):
    return classroom.dashboard(get_current_account(db, request))


@app.post("/classes")
def create_class(request: Request, body: ClassCreateIn):
    return classroom.create_class(get_current_account(db, request), body.name)


@app.post("/classes/{class_id}/members")
def import_members(class_id: str, request: Request, body: RosterImportIn):
    return classroom.import_roster(get_current_account(db, request), class_id, body.members)


@app.get("/classes/{class_id}/members")
def list_class_members(class_id: str, request: Request):
    account = get_current_account(db, request)
    with db.connect() as conn:
        classroom._require_class_manager(conn, class_id, account)
        rows = conn.execute(
            "SELECT a.id, a.username, a.display_name, a.student_no, a.status, a.created_at "
            "FROM class_members cm JOIN accounts a ON a.id = cm.account_id "
            "WHERE cm.class_id = ? ORDER BY a.student_no, a.display_name",
            (class_id,),
        ).fetchall()
    return {"members": [dict(row) for row in rows]}


@app.get("/classes/member-template.xlsx")
def download_member_template(request: Request):
    get_current_account(db, request)
    return FileResponse(
        "outputs/class_roster_template.xlsx",
        filename="班级成员导入模板.xlsx",
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )


@app.post("/classes/{class_id}/members/import-excel")
async def import_members_excel(
    class_id: str,
    request: Request,
    file: UploadFile = File(...),
):
    if not file.filename or not file.filename.lower().endswith(".xlsx"):
        raise ValueError("请上传 .xlsx 格式的 Excel 文件")
    return classroom.import_roster_excel(
        get_current_account(db, request), class_id, await file.read()
    )


@app.post("/classes/{class_id}/todos")
def create_todo(class_id: str, request: Request, body: TodoCreateIn):
    return classroom.create_todo(get_current_account(db, request), class_id, body)


@app.get("/todos/{todo_id}")
def todo_detail(todo_id: str, request: Request):
    return classroom.todo_detail(get_current_account(db, request), todo_id)


@app.post("/todos/{todo_id}/submit")
async def submit_todo(
    todo_id: str,
    request: Request,
    note: str = Form(default=""),
    file: UploadFile | None = File(default=None),
):
    content = await file.read() if file else None
    return classroom.submit(
        get_current_account(db, request), todo_id, note,
        file.filename if file else None, content,
    )


@app.post("/todos/{todo_id}/remind")
def remind_todo(todo_id: str, request: Request):
    return classroom.remind_missing(get_current_account(db, request), todo_id)


@app.post("/todos/{todo_id}/reminders")
def schedule_todo_reminder(todo_id: str, request: Request, body: TodoReminderIn):
    return classroom.schedule_reminder(
        get_current_account(db, request), todo_id, body.remind_at
    )


@app.get("/todos/{todo_id}/submissions/{account_id}/file")
def download_submission(todo_id: str, account_id: str, request: Request):
    account = get_current_account(db, request)
    classroom.todo_detail(account, todo_id)
    if account["role"] != "manager" and account["id"] != account_id:
        raise PermissionDeniedError("只能下载自己的提交文件")
    with db.connect() as conn:
        row = conn.execute(
            "SELECT fa.storage_key, fa.original_filename, fa.content_type FROM file_assets fa "
            "WHERE fa.todo_id = ? AND fa.account_id = ? ORDER BY fa.created_at DESC LIMIT 1",
            (todo_id, account_id),
        ).fetchone()
    if not row:
        raise NotFoundError("该成员没有上传文件")
    from urllib.parse import quote

    return Response(
        content=storage.read(row["storage_key"]),
        media_type=row["content_type"] or "application/octet-stream",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(row['original_filename'])}"},
    )


@app.get("/health/ready")
def readiness():
    with db.connect() as conn:
        conn.execute("SELECT 1").fetchone()
    return {"status": "ready", "database": "ok", "environment": settings.environment}


@app.post("/agent/drafts")
def create_agent_draft(request: Request, body: AgentRequestIn):
    return drafts.create(get_current_account(db, request), body.class_id, body.text)


@app.get("/agent/drafts")
def list_agent_drafts(request: Request):
    return {"drafts": drafts.list(get_current_account(db, request))}


@app.get("/agent/drafts/{draft_id}")
def get_agent_draft(draft_id: str, request: Request):
    return drafts.get(get_current_account(db, request), draft_id)


@app.put("/agent/drafts/{draft_id}")
def update_agent_draft(draft_id: str, request: Request, body: AgentDraftUpdateIn):
    return drafts.update(get_current_account(db, request), draft_id, body.draft)


@app.post("/agent/drafts/{draft_id}/publish")
def publish_agent_draft(draft_id: str, request: Request):
    return drafts.publish(get_current_account(db, request), draft_id)


def activity_actor(request: Request, legacy_user_id: str | None = None) -> tuple[str, dict | None]:
    account = get_current_account(db, request, required=False)
    if account:
        return account["username"], account
    if settings.allow_legacy_api and legacy_user_id:
        return legacy_user_id, None
    raise PermissionDeniedError("请先登录后操作活动")


@app.post("/activities", status_code=status.HTTP_202_ACCEPTED)
def create_activity(body: ActivityCreate, request: Request):
    """活动落库后立即返回；Worker 在后台执行完整流程。"""
    actor, account = activity_actor(request, body.user_id)
    if account:
        if account["role"] != "manager":
            raise PermissionDeniedError("只有管理者可以发起活动")
        if not body.class_id:
            raise ValueError("请选择活动所属班级")
        with db.connect() as conn:
            classroom._require_class_manager(conn, body.class_id, account)
    return service.queue_activity(
        actor, body.text, body.publish_to_qq, body.class_id
    )


@app.get("/portal/activities")
def portal_activities(request: Request):
    account = get_current_account(db, request)
    with db.connect() as conn:
        if account["role"] == "manager":
            class_ids = [row["id"] for row in conn.execute(
                "SELECT c.id FROM classes c JOIN class_organizations co ON co.class_id = c.id "
                "JOIN organization_members om ON om.organization_id = co.organization_id "
                "WHERE om.account_id = ? AND om.role IN ('owner', 'manager')",
                (account["id"],),
            ).fetchall()]
            return {"activities": service.list_activities(account["username"], class_ids)}
        rows = conn.execute(
            "SELECT DISTINCT a.* FROM activities a JOIN tasks t ON t.activity_id = a.id "
            "JOIN activity_task_assignees ata ON ata.task_id = t.id WHERE ata.account_id = ? "
            "ORDER BY a.created_at DESC",
            (account["id"],),
        ).fetchall()
        return {"activities": [dict(row) for row in rows]}


@app.get("/integrations/qq/status")
def qq_status(request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return qq.status(actor)


@app.post("/internal/qq/events", include_in_schema=False)
async def qq_inbound_event(body: QQInboundIn, x_gateway_token: str = Header(default="")):
    if not settings.qq_gateway_token or x_gateway_token != settings.qq_gateway_token:
        return JSONResponse(status_code=401, content={"detail": "invalid gateway token"})
    from types import SimpleNamespace

    event = SimpleNamespace(
        event_type=body.event_type,
        chat_id=body.group_openid,
        user_id=body.sender_openid,
        user_name=body.sender_name,
        chat_scope="group",
        content=body.content.strip(),
        message_id=body.message_id,
        timestamp=body.timestamp,
    )
    reply = await process_group_message(db, qq, service.llm, event)
    return {"reply": reply}


@app.post("/activities/{activity_id}/messages")
def send_activity_message(
    activity_id: str, body: ActivityMessageIn, request: Request,
    user_id: str | None = None,
):
    actor, account = activity_actor(request, user_id)
    if account and account["role"] != "manager":
        raise PermissionDeniedError("只有管理者可以发送活动通知")
    key = f"manual:{activity_id}:{body.request_id}"
    with db.connect() as conn:
        from .tools.base import ToolContext
        from .tools.registry import get_tool

        check_activity_owner(conn, activity_id, actor)
        channel = conn.execute(
            "SELECT group_openid FROM activity_channels WHERE activity_id = ?", (activity_id,)
        ).fetchone()
        if not channel:
            raise NotFoundError("该活动没有绑定 QQ 群")
        try:
            return get_tool("send_qq_group_message").execute(
                ToolContext(conn=conn, user_id=actor, activity_id=activity_id),
                idempotency_key=key,
                group_openid=channel["group_openid"],
                content=body.content,
                kind="manual",
            )
        except Exception as exc:
            return JSONResponse(
                status_code=502,
                content={"detail": f"QQ 消息发送失败：{str(exc)[:500]}"},
            )


@app.get("/public/forms/{form_id}")
def public_form(form_id: str):
    with db.connect() as conn:
        row = conn.execute(
            "SELECT f.id, f.title, f.fields_json, a.title AS activity_title, a.plan_json "
            "FROM forms f JOIN activities a ON a.id = f.activity_id WHERE f.id = ?",
            (form_id,),
        ).fetchone()
        if not row:
            raise NotFoundError("报名表不存在")
        import json

        plan = json.loads(row["plan_json"]) if row["plan_json"] else {}
        return {
            "id": row["id"],
            "title": row["title"],
            "activity_title": row["activity_title"],
            "event_time": plan.get("event_time"),
            "description": plan.get("description", ""),
            "fields": json.loads(row["fields_json"]),
        }


@app.get("/forms/{form_id}", include_in_schema=False)
def public_form_page(form_id: str):
    return FileResponse(static_dir / "form.html")


@app.get("/runs/{run_id}")
def get_run(run_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    run = queue.get(run_id)
    if not run:
        raise NotFoundError(f"执行记录不存在: {run_id}")
    with db.connect() as conn:
        if run["kind"] == "activity":
            check_activity_owner(conn, run["ref_id"], actor)
        else:
            reminder = conn.execute(
                "SELECT activity_id FROM reminders WHERE id = ?", (run["ref_id"],)
            ).fetchone()
            if not reminder:
                raise NotFoundError(f"提醒不存在: {run['ref_id']}")
            check_activity_owner(conn, reminder["activity_id"], actor)
    return run


@app.get("/activities/{activity_id}")
def get_activity(activity_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return service.get_activity(actor, activity_id)


@app.post("/forms/{form_id}/registrations")
def submit_registration(form_id: str, body: RegistrationIn):
    return service.submit_registration(form_id, body.name, body.contact, body.extra)


@app.get("/activities/{activity_id}/form-stats")
def form_stats(activity_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return service.form_stats(actor, activity_id)


@app.post("/activities/{activity_id}/recap")
def recap(activity_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return service.generate_recap(actor, activity_id)


@app.post("/reminders/{reminder_id}/cancel")
def cancel_reminder(reminder_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return service.cancel_reminder(actor, reminder_id)


@app.patch("/tasks/{task_id}")
def update_task(
    task_id: str, body: TaskUpdate, request: Request,
    user_id: str | None = None,
):
    actor, account = activity_actor(request, user_id)
    if account and account["role"] == "participant":
        with db.connect() as conn:
            assigned = conn.execute(
                "SELECT 1 FROM activity_task_assignees WHERE task_id = ? AND account_id = ?",
                (task_id, account["id"]),
            ).fetchone()
            if not assigned:
                raise PermissionDeniedError("该活动任务未分配给你")
            conn.execute("UPDATE tasks SET status = ? WHERE id = ?", (body.status.value, task_id))
            return dict(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())
    return service.update_task(actor, task_id, body.status.value)


@app.get("/activities/{activity_id}/calendar.ics")
def download_calendar(activity_id: str, request: Request, user_id: str | None = None):
    actor, _ = activity_actor(request, user_id)
    return Response(
        content=service.get_calendar_ics(actor, activity_id),
        media_type="text/calendar; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="activity.ics"'},
    )
