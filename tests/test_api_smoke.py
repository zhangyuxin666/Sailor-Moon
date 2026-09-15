import os
import tempfile

# 必须在导入 app.main 之前设置，避免使用默认数据库文件
os.environ["DATABASE_PATH"] = os.path.join(tempfile.mkdtemp(), "smoke.db")

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

client = TestClient(app)


def test_api_smoke():
    # 一句话发起活动
    resp = client.post("/activities", json={"user_id": "alice", "text": "班级秋游"})
    assert resp.status_code == 200
    detail = resp.json()
    activity_id = detail["activity"]["id"]
    form_id = detail["forms"][0]["id"]
    reminder_id = detail["reminders"][0]["id"]

    # 越权访问被拒
    assert client.get(f"/activities/{activity_id}", params={"user_id": "bob"}).status_code == 403

    # 报名 + 统计
    assert client.post(
        f"/forms/{form_id}/registrations", json={"name": "小明", "contact": "138"}
    ).status_code == 200
    stats = client.get(f"/activities/{activity_id}/form-stats", params={"user_id": "alice"}).json()
    assert stats["count"] == 1

    # 复盘
    assert client.post(f"/activities/{activity_id}/recap", params={"user_id": "alice"}).status_code == 200

    # 取消提醒
    resp = client.post(f"/reminders/{reminder_id}/cancel", params={"user_id": "alice"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "cancelled"
