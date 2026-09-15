"""冒烟测试：用 FastAPI TestClient 验证 POST /activities 全流程（跑完即删）。"""
import os
import tempfile

os.environ["DATABASE_PATH"] = os.path.join(tempfile.mkdtemp(), "smoke.db")
os.environ["LLM_API_KEY"] = ""  # 强制走 MockLLMClient

from fastapi.testclient import TestClient

from app.main import app

with TestClient(app) as client:
    r = client.get("/health")
    assert r.status_code == 200, r.text
    print("GET /health ->", r.json())

    r = client.post("/activities", json={"user_id": "alice", "text": "组织一场班级羽毛球赛"})
    assert r.status_code == 200, r.text
    data = r.json()
    print("POST /activities ->", data)
    aid = data["activity_id"]
    assert data["plan"]["title"] == "班级羽毛球赛"
    assert data["form_id"] and len(data["task_ids"]) == 4 and len(data["reminder_job_ids"]) == 2

    r = client.get(f"/activities/{aid}", params={"user_id": "alice"})
    assert r.status_code == 200 and r.json()["status"] == "active", r.text
    print("GET /activities/{id} (owner) -> 200")

    r = client.get(f"/activities/{aid}", params={"user_id": "bob"})
    assert r.status_code == 403, r.text
    print("GET /activities/{id} (非创建者) -> 403")

    r = client.post(f"/activities/{aid}/registrations", json={"name": "张三", "contact": "138"})
    assert r.status_code == 200, r.text
    r = client.get(f"/activities/{aid}/form-stats", params={"user_id": "alice"})
    assert r.json()["total"] == 1, r.text
    print("报名 + 统计 -> total =", r.json()["total"])

    r = client.post(f"/activities/{aid}/recap", params={"user_id": "alice"})
    assert r.status_code == 200 and r.json()["recap"], r.text
    print("POST /recap ->", r.json()["recap"][:30], "...")

    reminder_id = int(data["reminder_job_ids"][0].split("-")[1])
    r = client.post(f"/reminders/{reminder_id}/cancel", params={"user_id": "alice"})
    assert r.status_code == 200 and r.json()["cancelled"], r.text
    print(f"POST /reminders/{reminder_id}/cancel -> 200")

print("SMOKE TEST PASSED")
