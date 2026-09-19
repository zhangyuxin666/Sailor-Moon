from datetime import datetime, timedelta
from pathlib import Path
import pytest

from app.config import settings
from app.core.auth import create_account, set_password, verify_password
from app.core.exceptions import PermissionDeniedError
from app.models.schemas import RosterMemberIn, TodoCreateIn
from app.agent.llm import MockLLMClient
from app.jobs import JobQueue, Worker
from app.services.activity_service import ActivityService
from app.services.classroom_service import ClassroomService


def test_manager_roster_todo_and_participant_submission(db, reminders, monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "storage_local_path", str(tmp_path / "uploads"))
    with db.connect() as conn:
        manager = create_account(conn, "teacher", "王老师", "password123", "manager")
    service = ClassroomService(db, reminders=reminders)
    classroom = service.create_class(manager, "软件工程 5 班")
    roster = service.import_roster(
        manager,
        classroom["id"],
        [RosterMemberIn(
            username="student1", display_name="张三", student_no="2026001", password="12345678"
        )],
    )
    todo = service.create_todo(
        manager,
        classroom["id"],
        TodoCreateIn(
            title="实验报告",
            description="提交 PDF",
            deadline=(datetime.now() + timedelta(days=1)).isoformat(),
        ),
    )
    assert roster["count"] == 1
    assert todo["assigned_count"] == 1

    with db.connect() as conn:
        participant = dict(conn.execute(
            "SELECT * FROM accounts WHERE username = 'student1'"
        ).fetchone())
    assert verify_password("12345678", participant["password_hash"])
    assert service.dashboard(participant)["todos"][0]["my_status"] == "pending"

    result = service.submit(
        participant, todo["id"], "已经完成", "report.txt", b"homework"
    )
    assert result["status"] == "done"
    detail = service.todo_detail(manager, todo["id"])
    assert detail["participants"][0]["status"] == "done"
    assert detail["participants"][0]["original_filename"] == "report.txt"
    with db.connect() as conn:
        asset = conn.execute(
            "SELECT * FROM file_assets WHERE todo_id = ?", (todo["id"],)
        ).fetchone()
        audits = conn.execute("SELECT action FROM audit_logs").fetchall()
    assert asset["size_bytes"] == 8
    assert {row["action"] for row in audits} >= {"class.create", "roster.import", "todo.create", "todo.submit"}


def test_excel_roster_template_can_be_imported(db, reminders):
    with db.connect() as conn:
        manager = create_account(conn, "excelteacher", "李老师", "password123", "manager")
    service = ClassroomService(db, reminders=reminders)
    classroom = service.create_class(manager, "Excel 导入班")
    content = Path("outputs/class_roster_template.xlsx").read_bytes()

    result = service.import_roster_excel(manager, classroom["id"], content)

    assert result["count"] == 2
    assert [member["student_no"] for member in result["members"]] == ["2026001", "2026002"]


def test_organization_isolation_and_password_reset(db, reminders):
    with db.connect() as conn:
        first = create_account(conn, "manager1", "一班老师", "password123", "manager")
        second = create_account(conn, "manager2", "二班老师", "password123", "manager")
    service = ClassroomService(db, reminders=reminders)
    classroom = service.create_class(first, "一班")

    with pytest.raises(PermissionDeniedError):
        service.import_roster(
            second,
            classroom["id"],
            [RosterMemberIn(username="student2", display_name="学生", student_no="2", password="12345678")],
        )

    with db.connect() as conn:
        set_password(conn, first["id"], "new-password-123", force_change=True)
        account = conn.execute(
            "SELECT a.password_hash, sec.force_password_change FROM accounts a "
            "JOIN account_security sec ON sec.account_id = a.id WHERE a.id = ?",
            (first["id"],),
        ).fetchone()
    assert verify_password("new-password-123", account["password_hash"])
    assert account["force_password_change"] == 1


def test_ai_activity_assigns_real_class_members(db, reminders):
    with db.connect() as conn:
        manager = create_account(conn, "activitymanager", "活动负责人", "password123", "manager")
    classroom_service = ClassroomService(db, reminders=reminders)
    classroom = classroom_service.create_class(manager, "活动班")
    classroom_service.import_roster(
        manager,
        classroom["id"],
        [
            RosterMemberIn(username="member1", display_name="张同学", student_no="1", password="12345678"),
            RosterMemberIn(username="member2", display_name="李同学", student_no="2", password="12345678"),
        ],
    )
    queue = JobQueue(db)
    activity_service = ActivityService(db, MockLLMClient(), reminders, queue=queue)
    queued = activity_service.queue_activity(
        manager["username"], "举办班级分享会", publish_to_qq=False, class_id=classroom["id"]
    )
    assert Worker(queue, activity_service, reminders, classroom_service).run_once()

    detail = activity_service.get_activity(manager["username"], queued["activity_id"])
    assert {task["assignee"] for task in detail["tasks"]} == {"张同学", "李同学"}
    with db.connect() as conn:
        links = conn.execute(
            "SELECT * FROM activity_task_assignees"
        ).fetchall()
    assert len(links) == len(detail["tasks"])
