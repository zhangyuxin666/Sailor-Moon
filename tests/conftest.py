import os
import tempfile

import pytest

os.environ["DATABASE_URL"] = "sqlite:///" + os.path.join(
    tempfile.mkdtemp(), "test-suite.db"
).replace("\\", "/")
os.environ["LLM_API_KEY"] = ""
os.environ["ALLOW_LEGACY_API"] = "true"
os.environ["ENVIRONMENT"] = "test"

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
