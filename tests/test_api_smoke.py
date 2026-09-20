import os
import tempfile
import uuid

# 必须在导入 app.main 之前设置，避免使用默认数据库文件
os.environ["DATABASE_URL"] = "sqlite:///" + os.path.join(tempfile.mkdtemp(), "smoke.db").replace("\\", "/")
os.environ["LLM_API_KEY"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.agent.llm import MockLLMClient  # noqa: E402
from app.jobs import Worker  # noqa: E402
from app.main import app, queue, reminders, service  # noqa: E402

service.llm = MockLLMClient()
service.orchestrator.llm = service.llm
client = TestClient(app)


def test_web_app():
    redirect = client.get("/", follow_redirects=False)
    assert redirect.status_code in (302, 307)
    assert redirect.headers["location"] == "/login"
    assert redirect.headers["cache-control"] == "no-store"

    response = client.get("/login")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store, max-age=0"
    assert response.headers["vary"] == "Cookie"
    assert "活动管家" in response.text
    assert client.get("/static/styles.css").status_code == 200
    assert client.get("/static/app.js").status_code == 200


def test_qq_status_and_public_form(monkeypatch):
    status = client.get("/integrations/qq/status", params={"user_id": "alice"})
    assert status.status_code == 200
    assert isinstance(status.json()["configured"], bool)

    from app.config import settings
    monkeypatch.setattr(settings, "qq_gateway_token", "test-gateway-token")
    inbound = client.post(
        "/internal/qq/events",
        headers={"X-Gateway-Token": "test-gateway-token"},
        json={
            "group_openid": "test-group",
            "sender_openid": "test-member",
            "content": "你是什么",
            "message_id": f"test-message-{uuid.uuid4().hex}",
        },
    )
    assert inbound.status_code == 200
    assert "活动管家" in inbound.json()["reply"]

    queued = client.post(
        "/activities",
        json={"user_id": "form-owner", "text": "公开报名测试", "publish_to_qq": False},
    ).json()
    assert Worker(queue, service, reminders).run_once()
    detail = client.get(
        f"/activities/{queued['activity_id']}", params={"user_id": "form-owner"}
    ).json()
    form_id = detail["forms"][0]["id"]
    assert client.get(f"/forms/{form_id}").status_code == 200
    form_data = client.get(f"/public/forms/{form_id}").json()
    assert form_data["id"] == form_id
    assert form_data["activity_title"]


def test_api_smoke():
    # 一句话发起活动
    resp = client.post("/activities", json={"user_id": "alice", "text": "班级秋游"})
    assert resp.status_code == 202
    queued = resp.json()
    activity_id = queued["activity_id"]
    run_id = queued["run_id"]
    assert client.get(f"/runs/{run_id}", params={"user_id": "alice"}).json()["status"] == "queued"
    assert client.get(f"/runs/{run_id}", params={"user_id": "bob"}).status_code == 403
    assert Worker(queue, service, reminders).run_once()
    assert client.get(f"/runs/{run_id}", params={"user_id": "alice"}).json()["status"] == "succeeded"
    detail = client.get(f"/activities/{activity_id}", params={"user_id": "alice"}).json()
    form_id = detail["forms"][0]["id"]
    reminder_id = detail["reminders"][0]["id"]
    task_id = detail["tasks"][0]["id"]

    # 越权访问被拒
    assert client.get(f"/activities/{activity_id}", params={"user_id": "bob"}).status_code == 403

    # 任务状态可更新，复盘会使用最新状态；其他用户不能修改
    assert client.patch(
        f"/tasks/{task_id}", params={"user_id": "bob"}, json={"status": "done"}
    ).status_code == 403
    assert client.patch(
        f"/tasks/{task_id}", params={"user_id": "alice"}, json={"status": "invalid"}
    ).status_code == 422
    task = client.patch(
        f"/tasks/{task_id}", params={"user_id": "alice"}, json={"status": "done"}
    ).json()
    assert task["status"] == "done"

    # 下载已生成的日历文件，不会重复创建事件
    calendar_url = f"/activities/{activity_id}/calendar.ics"
    assert client.get(calendar_url, params={"user_id": "bob"}).status_code == 403
    calendar = client.get(calendar_url, params={"user_id": "alice"})
    assert calendar.status_code == 200
    assert calendar.headers["content-type"].startswith("text/calendar")
    assert "BEGIN:VCALENDAR" in calendar.text
    assert "DTSTART:" in calendar.text

    # 报名 + 统计
    assert client.post(
        f"/forms/{form_id}/registrations", json={"name": "小明", "contact": "138"}
    ).status_code == 200
    stats = client.get(f"/activities/{activity_id}/form-stats", params={"user_id": "alice"}).json()
    assert stats["count"] == 1

    # 复盘
    recap = client.post(f"/activities/{activity_id}/recap", params={"user_id": "alice"})
    assert recap.status_code == 200
    assert "-done" in recap.json()["recap"]

    # 取消提醒
    resp = client.post(f"/reminders/{reminder_id}/cancel", params={"user_id": "alice"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "cancelled"
