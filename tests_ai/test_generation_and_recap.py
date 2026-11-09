from fastapi.testclient import TestClient
from ai_service.llm import AiEngine
from ai_service.models import AssigneeCandidate
from ai_service.config import settings
from ai_service.main import app
from ai_service.rag import RagStore, Embeddings
from ai_service import llm
from datetime import datetime
import json


def test_offline_dates_remain_empty_and_require_real_confirmation():
    engine=AiEngine()
    plan=engine.plan("下周组织活动",[])
    draft=engine.analyze("组织活动，需要报名","")
    assignment=engine.analyze("下周收作业","")
    assert plan.event_time==draft.event_time==assignment.deadline==""
    assert draft.generation.mode=="offline"
    assert draft.generation.pending_fields==["event_time"]
    assert assignment.generation.pending_fields==["deadline"]
    assert draft.missing_information


def test_model_failure_has_safe_reason_and_no_invented_date(monkeypatch):
    monkeypatch.setattr(settings,"llm_api_key","local-fixture")
    def failure(*a):raise RuntimeError("fake-secret upstream")
    monkeypatch.setattr(AiEngine,"_chat",failure)
    result=AiEngine().plan("活动",[])
    assert result.generation.fallback_reason=="model_unavailable"
    assert result.event_time==""
    assert "fake-secret" not in result.generation.model_dump_json()


def test_relative_activity_range_is_storable_in_model_and_offline_modes(monkeypatch):
    class FixedTime(datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(2026, 11, 9, 14, 0, tzinfo=tz)

    monkeypatch.setattr(llm, "datetime", FixedTime)
    engine = AiEngine()
    text = "我要在本周五10：00–11：00举办班会，帮我提醒大家"
    expected = "2026-11-13T10:00:00+08:00"
    offline = engine.analyze(text, "")
    assert offline.event_time == expected and offline.generation.pending_fields == []
    assert engine.plan(text, []).event_time == expected
    assert engine.analyze("本周五10:00报名，下周五举办班会", "").event_time == ""

    monkeypatch.setattr(settings, "llm_api_key", "local-fixture")

    def chat(self, system, user):
        assert "2026-11-09T14:00:00+08:00" in user
        if "事务规划" in system:
            return json.dumps({
                "title": "班会", "intent_type": "activity", "complexity": "standard",
                "summary": "举行班会", "workflow": {}, "event_time": "本周五10:00–11:00",
            })
        return json.dumps({
            "title": "班会", "event_time": "2026-11-13T10:00:00+08:00/2026-11-13T11:00:00+08:00",
            "tasks": [{"title": "准备", "deadline": "2026-11-12 19:00"},
                      {"title": "提醒", "deadline": "2026-11-13"}],
        })

    monkeypatch.setattr(AiEngine, "_chat", chat)
    assert engine.analyze(text, "").event_time == expected
    plan = engine.plan(text + "，需要分工", [])
    assert plan.generation.mode == "model" and plan.event_time == expected
    assert [task.deadline for task in plan.tasks] == ["2026-11-12T19:00:00", ""]

    next_week = "下周五下午两点举办班会"
    expected_next_week = "2026-11-20T14:00:00+08:00"
    assert engine.analyze(next_week, "").event_time == expected_next_week
    assert engine.plan(next_week, []).event_time == expected_next_week


def test_model_cannot_forge_source_or_generation_metadata(monkeypatch):
    monkeypatch.setattr(settings,"llm_api_key","local-fixture")
    monkeypatch.setattr(AiEngine,"_chat",lambda *a:json.dumps({"title":"模型活动","generation":{"mode":"offline","sources":[{"id":"forged"}]}}))
    result=AiEngine().plan("活动",[])
    assert result.generation.mode=="model" and not result.generation.sources


def test_retrieval_failure_is_distinct_from_no_evidence(monkeypatch):
    store=RagStore()
    monkeypatch.setattr(store,"search",lambda *a:[])
    assert store.retrieve("org-a","活动")[1]["retrieval_status"]=="empty"
    def failure(*a):raise RuntimeError("private connection URL")
    monkeypatch.setattr(store,"search",failure)
    _,metadata=store.retrieve("org-a","活动")
    assert metadata["retrieval_status"]=="unavailable"
    assert "private" not in str(metadata)


def test_recap_model_receives_only_bounded_aggregate_whitelist(monkeypatch):
    monkeypatch.setattr(settings,"llm_api_key","local-fixture")
    captured=[]
    def chat(self,system,user):captured.append(json.loads(user));return "建议逐项跟进未完成事项。"
    monkeypatch.setattr(AiEngine,"_chat",chat)
    response=TestClient(app).post('/internal/ai/recap',headers={'X-Internal-Token':settings.ai_internal_token},json={'activity_title':'private-person','task_summary':'private-contact','organization_id':'private-org','stats':{'registration_active':3,'tasks_total':4,'feedback_count':1,'feedback_rating_counts':{'5':1,'private-name':7},'feedback_comments':['private-feedback'],'registrations':[{'name':'private-name','contact':'private-phone','extra':'private-answer'}],'deliveries':{'sent':2,'private-id':9}}})
    assert response.status_code==200
    assert captured==[{'aggregates':{'registration_active':3,'tasks_total':4,'feedback_count':1,'steps':{},'deliveries':{'sent':2},'feedback_rating_counts':{'5':1}}}]
    assert 'private' not in json.dumps(captured)
    assert response.json()['generation']['mode']=='model'


def test_embedding_profile_changes_with_model_endpoint_but_not_credentials(monkeypatch):
    embed=Embeddings();assert embed.profile().startswith('local_hash_v1')
    monkeypatch.setattr(settings,'embedding_api_key','fake-one');monkeypatch.setattr(settings,'embedding_base_url','http://local-a.invalid')
    first=embed.profile();monkeypatch.setattr(settings,'embedding_api_key','fake-two');assert embed.profile()==first
    monkeypatch.setattr(settings,'embedding_base_url','http://local-b.invalid');assert embed.profile()!=first
