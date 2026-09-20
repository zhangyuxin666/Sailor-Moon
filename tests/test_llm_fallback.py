import httpx

from app.agent.llm import FallbackLLMClient, MockLLMClient, OpenAICompatibleClient


def test_remote_llm_timeout_falls_back_to_offline_rules(monkeypatch):
    remote = OpenAICompatibleClient("test-key", "https://example.invalid/v1", "test-model")

    def timeout(*_args, **_kwargs):
        raise httpx.ConnectTimeout("model service timed out")

    monkeypatch.setattr(remote, "_chat", timeout)
    client = FallbackLLMClient(remote, MockLLMClient())

    draft = client.analyze_work_request("组织一次班级活动", "测试班；成员数：10")

    assert draft.intent_type == "activity"
    assert draft.workflow.publish_message is True
