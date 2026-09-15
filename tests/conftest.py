import pytest

from app.agent.llm import MockLLMClient
from app.models.database import Database
from app.scheduler.reminders import ReminderService
from app.services.activity_service import ActivityService


@pytest.fixture
def db(tmp_path):
    return Database(str(tmp_path / "test.db"))


@pytest.fixture
def reminders(db):
    svc = ReminderService(db)
    yield svc
    svc.shutdown()


@pytest.fixture
def service(db, reminders):
    return ActivityService(db, MockLLMClient(), reminders)
