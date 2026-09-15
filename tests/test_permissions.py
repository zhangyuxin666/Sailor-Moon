import pytest

from app.core.exceptions import NotFoundError, PermissionDeniedError


def test_owner_can_access(service):
    detail = service.create_activity_from_text("alice", "迎新晚会")
    assert service.get_activity("alice", detail["activity"]["id"])


def test_non_owner_is_denied(service):
    detail = service.create_activity_from_text("alice", "迎新晚会")
    activity_id = detail["activity"]["id"]
    with pytest.raises(PermissionDeniedError):
        service.get_activity("bob", activity_id)
    with pytest.raises(PermissionDeniedError):
        service.form_stats("bob", activity_id)
    with pytest.raises(PermissionDeniedError):
        service.generate_recap("bob", activity_id)


def test_non_owner_cannot_cancel_reminder(service):
    detail = service.create_activity_from_text("alice", "迎新晚会")
    reminder_id = detail["reminders"][0]["id"]
    with pytest.raises(PermissionDeniedError):
        service.cancel_reminder("bob", reminder_id)


def test_missing_activity_raises_not_found(service):
    with pytest.raises(NotFoundError):
        service.get_activity("alice", "no-such-id")
