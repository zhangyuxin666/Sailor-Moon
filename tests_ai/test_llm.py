from ai_service.llm import AiEngine
from ai_service.models import FormField, WorkDraft
from ai_service.rag import Embeddings
from ai_service.config import settings
from ai_service.main import app
from fastapi.testclient import TestClient


def test_offline_plan_only_uses_real_assignees(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    plan = AiEngine().plan("组织班级秋游", ["成员甲", "成员乙"])
    assert plan.tasks
    assert {task.assignee for task in plan.tasks} == {"成员甲", "成员乙"}
    assert {intent.tool for intent in plan.tool_intents} >= {
        "create_form", "assign_tasks", "create_calendar", "schedule_reminder", "publish_message"
    }


def test_offline_plan_does_not_invent_assignees(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    plan = AiEngine().plan("组织班级秋游", [])
    assert plan.tasks == []
    assert "assign_tasks" not in {intent.tool for intent in plan.tool_intents}


def test_offline_agent_selects_minimum_assignment_workflow(monkeypatch):
    monkeypatch.setattr("ai_service.llm.settings.llm_api_key", "")
    draft = AiEngine().analyze("周五前收数据库实验报告 PDF", "软件工程班")
    assert draft.intent_type == "assignment"
    assert draft.complexity == "simple"
    assert draft.workflow.collect_submission is True
    assert draft.workflow.assign_tasks is False


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
