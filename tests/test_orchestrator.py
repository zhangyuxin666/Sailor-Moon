def test_full_flow_from_one_sentence(service, db):
    """一句话发起活动：策划、问卷、任务派发、提醒全部自动生成。"""
    detail = service.create_activity_from_text("alice", "下周三晚上7点社团招新宣讲会")

    activity = detail["activity"]
    assert activity["title"]
    assert detail["plan"]["materials"]           # 物料清单
    assert len(detail["plan"]["tasks"]) >= 3     # 任务分工

    assert len(detail["forms"]) == 1             # 报名问卷已创建
    assert len(detail["tasks"]) >= 3             # 任务落库
    assert len(detail["reminders"]) == 1         # 提醒已调度
    assert detail["reminders"][0]["status"] == "scheduled"

    # 每个负责人收到了派发消息
    with db.connect() as conn:
        recipients = {
            r["recipient"]
            for r in conn.execute(
                "SELECT recipient FROM messages WHERE activity_id = ?", (activity["id"],)
            ).fetchall()
        }
    assert recipients == {t["assignee"] for t in detail["tasks"]}

    # 编排步骤全部记录且成功
    step_names = [s["step"] for s in detail["steps"]]
    assert step_names == [
        "generate_plan",
        "create_form",
        "assign_tasks",
        "create_calendar_event",
        "schedule_reminder",
    ]


def test_registration_and_stats(service):
    detail = service.create_activity_from_text("alice", "班级秋游")
    form_id = detail["forms"][0]["id"]
    service.submit_registration(form_id, "小明", "13800000000", {})
    service.submit_registration(form_id, "小红", "13900000000", {})

    stats = service.form_stats("alice", detail["activity"]["id"])
    assert stats["count"] == 2
    assert {r["name"] for r in stats["registrations"]} == {"小明", "小红"}


def test_recap(service):
    detail = service.create_activity_from_text("alice", "羽毛球友谊赛")
    result = service.generate_recap("alice", detail["activity"]["id"])
    assert "复盘" in result["recap"]
