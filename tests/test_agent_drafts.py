from app.agent.llm import MockLLMClient
from app.core.auth import create_account
from app.jobs import JobQueue, Worker
from app.models.schemas import RosterMemberIn
from app.services.activity_service import ActivityService
from app.services.agent_draft_service import AgentDraftService
from app.services.classroom_service import ClassroomService


def build_services(db, reminders):
    with db.connect() as conn:
        manager = create_account(conn, "agentmanager", "Agent 管理者", "password123", "manager")
    classroom = ClassroomService(db, reminders=reminders)
    class_info = classroom.create_class(manager, "Agent 测试班")
    classroom.import_roster(
        manager,
        class_info["id"],
        [RosterMemberIn(username="agentmember", display_name="成员甲", student_no="1", password="12345678")],
    )
    queue = JobQueue(db)
    activities = ActivityService(db, MockLLMClient(), reminders, queue=queue)
    drafts = AgentDraftService(db, MockLLMClient(), classroom, activities)
    return manager, class_info, classroom, queue, activities, drafts


def test_simple_assignment_does_not_create_activity_workflow(db, reminders):
    manager, class_info, _classroom, _queue, _activities, drafts = build_services(db, reminders)
    draft = drafts.create(manager, class_info["id"], "周五前收数据库实验作业 PDF")

    assert draft["draft"]["intent_type"] == "assignment"
    assert draft["draft"]["complexity"] == "simple"
    assert draft["draft"]["workflow"]["assign_tasks"] is False

    result = drafts.publish(manager, draft["id"])
    assert result["result_type"] == "todo"
    with db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) AS count FROM todos").fetchone()["count"] == 1
        assert conn.execute("SELECT COUNT(*) AS count FROM activities").fetchone()["count"] == 0
        assert conn.execute("SELECT COUNT(*) AS count FROM tasks").fetchone()["count"] == 0


def test_complex_activity_uses_full_workflow_after_confirmation(db, reminders):
    manager, class_info, classroom, queue, activities, drafts = build_services(db, reminders)
    draft = drafts.create(manager, class_info["id"], "组织班级秋游，需要报名分工和提醒")
    assert draft["draft"]["intent_type"] == "activity"
    assert draft["draft"]["workflow"]["assign_tasks"] is True

    result = drafts.publish(manager, draft["id"])
    assert result["result_type"] == "activity"
    assert Worker(queue, activities, reminders, classroom).run_once()
    detail = activities.get_activity(manager["username"], result["result_id"])
    assert detail["forms"]
    assert detail["tasks"]
    assert detail["reminders"]
