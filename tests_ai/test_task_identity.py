import json

import pytest
from pydantic import ValidationError

from ai_service.config import settings
from ai_service.llm import AiEngine
from ai_service.main import app
from ai_service.models import AssigneeCandidate, PlanRequest
from fastapi.testclient import TestClient


def members():
    return [
        AssigneeCandidate(account_id="a1", display_name="同名"),
        AssigneeCandidate(account_id="a2", display_name="同名"),
    ]


def test_offline_tasks_distinguish_duplicate_names_by_account_id(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "")
    plan = AiEngine().plan("班级活动", members())
    assert {task.account_id for task in plan.tasks} == {"a1", "a2"}
    assert {task.assignee for task in plan.tasks} == {"同名"}


def test_remote_result_uses_canonical_name_for_selected_id(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "local-test-only")
    monkeypatch.setattr(AiEngine, "_chat", lambda *args: json.dumps({
        "title": "活动", "tasks": [{"title": "准备", "account_id": "a2", "assignee": "模型乱写"}],
    }))
    plan = AiEngine().plan("活动", members())
    assert len(plan.tasks) == 1
    assert plan.tasks[0].account_id == "a2"
    assert plan.tasks[0].assignee == "同名"


@pytest.mark.parametrize("identity", [{"account_id": "foreign"}, {"assignee": "同名"}])
def test_invalid_remote_identity_is_preserved_pending_without_assigning_by_name(monkeypatch, identity):
    monkeypatch.setattr(settings, "llm_api_key", "local-test-only")
    monkeypatch.setattr(AiEngine, "_chat", lambda *args: json.dumps({
        "title": "活动", "tasks": [{"title": "准备", **identity}],
    }))
    plan = AiEngine().plan("活动", members())
    assert plan.tasks
    assert len(plan.tasks) == 1
    assert plan.tasks[0].title == "准备"
    assert plan.tasks[0].account_id == ""
    assert plan.tasks[0].assignee == "待分配"


def test_internal_request_rejects_name_only_candidate_lists():
    with pytest.raises(ValidationError):
        PlanRequest(raw_input="活动", available_assignees=["同名"])


def test_plan_endpoint_keeps_account_ids_and_server_organization(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "")
    seen = []
    monkeypatch.setattr("ai_service.main.rag.retrieve",
                        lambda org, query: (seen.append((org, query)) or "", {"retrieval_status":"empty","sources":[]}))
    response = TestClient(app).post("/internal/ai/plan",
        headers={"X-Internal-Token": settings.ai_internal_token},
        json={"raw_input": "活动", "organization_id": "org-a",
              "available_assignees": [member.model_dump() for member in members()]})
    assert response.status_code == 200
    assert seen == [("org-a", "活动")]
    assert {task["account_id"] for task in response.json()["tasks"]} == {"a1", "a2"}


def test_offline_tasks_without_members_are_kept_for_manual_assignment(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "")
    plan = AiEngine().plan("组织活动", [])
    assert len(plan.tasks) == 4
    assert all(task.account_id == "" and task.assignee == "待分配" for task in plan.tasks)
