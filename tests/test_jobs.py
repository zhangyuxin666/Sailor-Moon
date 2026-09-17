from app.jobs import JobQueue, Worker


def test_activity_job_is_persistent_and_idempotent(db, service, reminders):
    queue = JobQueue(db)
    service.queue = queue
    first = service.queue_activity("alice", "班级迎新会")
    assert queue.get(first["run_id"])["status"] == "queued"
    worker = Worker(queue, service, reminders)
    assert worker.run_once() is True
    assert queue.get(first["run_id"])["status"] == "succeeded"
    assert service.get_activity("alice", first["activity_id"])["activity"]["status"] == "ready"


def test_enqueue_same_reference_only_creates_one_job(db):
    queue = JobQueue(db)
    one = queue.enqueue("reminder", "reminder-1")
    two = queue.enqueue("reminder", "reminder-1")
    assert one["id"] == two["id"]
