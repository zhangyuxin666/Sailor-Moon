import json
from datetime import datetime

from ai_service.llm import AiEngine, _normalize_work_draft
from ai_service.draft_time import SHANGHAI, request_time
from ai_service.models import AssigneeCandidate, FormField, WorkDraft
from ai_service.rag import Embeddings
from ai_service.config import settings
from ai_service.main import app
from fastapi.testclient import TestClient


def test_offline_plan_only_uses_real_assignees(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    plan = AiEngine().plan("组织班级秋游，需要分工", [
        AssigneeCandidate(account_id="a1", display_name="成员甲"),
        AssigneeCandidate(account_id="a2", display_name="成员乙"),
    ])
    assert plan.tasks
    assert {task.assignee for task in plan.tasks} == {"成员甲", "成员乙"}
    assert {task.account_id for task in plan.tasks} == {"a1", "a2"}
    assert {intent.tool for intent in plan.tool_intents} >= {
        "create_form", "assign_tasks", "create_calendar", "schedule_reminder", "publish_message"
    }


def test_offline_plan_does_not_invent_assignees(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    plan = AiEngine().plan("组织班级秋游，需要分工", [])
    assert plan.tasks
    assert all(task.account_id == "" and task.assignee == "待分配" for task in plan.tasks)
    assert "assign_tasks" in {intent.tool for intent in plan.tool_intents}


def test_offline_agent_selects_minimum_assignment_workflow(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    draft = AiEngine().analyze("周五前收数据库实验报告 PDF", "软件工程班")
    assert draft.intent_type == "assignment"
    assert draft.complexity == "simple"
    assert draft.workflow.collect_submission is True
    assert draft.workflow.assign_tasks is False


def test_plain_activity_only_enables_requested_reminder_even_if_model_adds_tasks(monkeypatch):
    engine = AiEngine()
    text = "下周五下午两点开班会，提前半小时站内提醒"
    monkeypatch.setattr(settings, "llm_api_key", "")
    offline = engine.analyze(text, "")
    plan = engine.plan(text, [])
    assert not plan.tasks and plan.remind_minutes_before == 30
    assert "assign_tasks" not in {intent.tool for intent in plan.tool_intents}
    monkeypatch.setattr(settings, "llm_api_key", "local-fixture")
    monkeypatch.setattr(engine, "_chat", lambda *_: json.dumps({
        "intent_type": "activity", "complexity": "standard", "title": "班会", "summary": "开班会",
        "workflow": {"assign_tasks": True, "schedule_reminder": True, "publish_message": True},
    }))
    remote = engine.analyze(text, "")
    for draft in (offline, remote):
        assert draft.intent_type == "activity"
        assert draft.workflow.schedule_reminder
        assert not draft.workflow.assign_tasks
        assert not draft.workflow.create_form
        assert not draft.workflow.create_calendar
        assert not draft.workflow.publish_message


def test_activity_with_registration_is_not_misclassified_as_survey(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    draft = AiEngine().analyze(
        "下周五下午2点在教学楼A302组织一场AI工具分享会，预计30人参加，需要报名、安排主持和设备，并提前一天提醒。",
        "软件工程班",
    )
    assert draft.intent_type == "activity"
    assert draft.title == "AI工具分享会"
    assert draft.workflow.create_form is True
    assert draft.workflow.assign_tasks is True
    assert draft.workflow.schedule_reminder is True


def test_work_draft_normalizes_provider_form_fields_and_workflow_defaults():
    payload = _normalize_work_draft({
        "intent_type": "activity",
        "complexity": "complex",
        "title": "AI工具分享会",
        "summary": "组织一次主题分享活动",
        "form_fields": [{"name": "是否参加", "label": "是否参加", "type": "radio"}],
        "workflow": {"create_form": True},
    })
    draft = WorkDraft.model_validate(payload)
    assert draft.form_fields[0].name == "field_1"
    assert draft.form_fields[0].type == "text"
    assert draft.workflow.create_form is True
    assert draft.workflow.publish_message is True


def test_local_embedding_is_stable_and_normalized(monkeypatch):
    monkeypatch.setattr("ai_service.rag.settings.embedding_base_url", "")
    first = Embeddings().create("活动安全预案")
    second = Embeddings().create("活动安全预案")
    assert first == second
    assert len(first) == 1536
    assert abs(sum(value * value for value in first) - 1.0) < 1e-6


def test_model_output_normalization_accepts_common_provider_variants():
    assert FormField(name="phone", label="电话", type="tel").type == "text"
    draft = WorkDraft.model_validate({
        "intent_type": "notice",
        "complexity": "simple",
        "title": "通知",
        "summary": "测试",
        "deadline": None,
        "event_time": None,
        "workflow": {"publish_message": True},
    })
    assert draft.deadline == ""
    assert draft.event_time == ""


def test_common_chinese_times_and_ambiguous_choices():
    now = datetime(2026, 11, 9, 14, 0, tzinfo=SHANGHAI)
    assert request_time("2026年11月13日下午两点半开班会", now) == "2026-11-13T14:30:00+08:00"
    assert request_time("周五上午十点到十一点开会", now) == "2026-11-13T10:00:00+08:00"
    assert request_time("明天晚上七点十五分开始", now) == "2026-11-10T19:15:00+08:00"
    assert request_time("明天晚上七点15分开始", now) == "2026-11-10T19:15:00+08:00"
    assert request_time("周五十点或十一点开会", now) == ""
    assert request_time("周五开会", now) == ""


def test_provider_variants_keep_a_storable_plan_and_honor_explicit_exclusions(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "local-fixture")

    def chat(self, system, user):
        fields = [
            {"name": "choice", "label": "参加方式", "type": "radio", "options": "线上、线下", "required": "false"},
            {"name": "choice", "label": "备选时间", "type": "select", "options": []},
        ]
        if "事务规划" in system:
            return json.dumps({
                "title": "班会", "intent_type": "activity", "complexity": "standard",
                "summary": "举办班会", "form_fields": fields,
                "workflow": {"schedule_reminder": "yes", "create_form": True, "publish_message": True},
            })
        return json.dumps({
            "title": "班会", "form_fields": fields, "remind_minutes_before": "1小时",
            "tasks": [{"title": "准备", "key": "prep", "priority": "高"},
                      {"title": "检查", "account_id": None, "depends_on": "prep", "priority": "紧急"}],
        })

    monkeypatch.setattr(AiEngine, "_chat", chat)
    text = "周五十点开班会，需要分工，提前半小时提醒，不用报名，别发到QQ群"
    engine = AiEngine()
    draft = engine.analyze(text, "")
    plan = engine.plan(text, [])
    assert draft.generation.mode == plan.generation.mode == "model"
    assert draft.workflow.schedule_reminder
    assert not draft.workflow.create_form and not draft.workflow.publish_message
    for fields in (draft.form_fields, plan.form_fields):
        assert len({field.name for field in fields}) == 2
        assert fields[0].type == "select" and fields[0].options == ["线上", "线下"]
        assert not fields[0].required and fields[1].type == "text"
    assert plan.remind_minutes_before == 30
    assert [task.priority for task in plan.tasks] == ["high", "urgent"]
    assert plan.tasks[1].depends_on == ["prep"] and plan.tasks[1].account_id == ""


def test_explicit_alternatives_remain_pending_even_if_model_picks_one(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "local-fixture")
    monkeypatch.setattr(AiEngine, "_chat", lambda *args: json.dumps({
        "title": "班会", "intent_type": "activity", "complexity": "simple",
        "summary": "班会", "workflow": {}, "event_time": "2026-11-13T10:00:00+08:00",
    }))
    draft = AiEngine().analyze("周五十点或十一点开会，时间待定", "")
    assert draft.event_time == "" and draft.generation.pending_fields == ["event_time"]


def test_offline_intent_uses_activity_goal_and_honors_opt_out():
    draft = AiEngine().analyze("周五下午两点举办班会，收集演示材料，不用分工，只在站内提醒", "")
    assert draft.intent_type == "activity"
    assert draft.workflow.schedule_reminder
    assert not draft.workflow.assign_tasks and not draft.workflow.publish_message


def test_embedding_is_internal_and_fastapi_has_no_document_write_route(monkeypatch):
    monkeypatch.setattr("ai_service.rag.settings.embedding_base_url", "")
    client = TestClient(app)
    assert client.post("/internal/ai/embed", json={"text": "知识"}).status_code == 401
    response = client.post(
        "/internal/ai/embed",
        headers={"X-Internal-Token": settings.ai_internal_token},
        json={"text": "知识"},
    )
    assert response.status_code == 200
    assert len(response.json()["embedding"]) == 1536
    assert client.post(
        "/internal/rag/documents",
        headers={"X-Internal-Token": settings.ai_internal_token},
        json={"source": "x", "content": "y"},
    ).status_code == 404
