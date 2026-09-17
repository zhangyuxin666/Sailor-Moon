"""本地冒烟测试：API 入队，Worker 执行，再查询活动。"""
import os
import tempfile

os.environ["DATABASE_URL"] = "sqlite:///" + os.path.join(tempfile.mkdtemp(), "smoke.db").replace("\\", "/")
os.environ["LLM_API_KEY"] = ""

from fastapi.testclient import TestClient
from app.jobs import Worker
from app.main import app, queue, reminders, service

with TestClient(app) as client:
    assert client.get("/health").json() == {"status": "ok"}
    response = client.post("/activities", json={"user_id": "alice", "text": "组织一场班级羽毛球赛"})
    assert response.status_code == 202, response.text
    queued = response.json()
    assert Worker(queue, service, reminders).run_once()
    assert client.get(f"/runs/{queued['run_id']}", params={"user_id": "alice"}).json()["status"] == "succeeded"
    detail = client.get(f"/activities/{queued['activity_id']}", params={"user_id": "alice"})
    assert detail.status_code == 200 and detail.json()["plan"]
    print("SMOKE TEST PASSED")
