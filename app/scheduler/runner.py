from ..config import settings
from ..jobs import JobQueue
from ..models.database import Database
from .reminders import ReminderService
from threading import Event


if __name__ == "__main__":
    db = Database(settings.database_url)
    service = ReminderService(db, queue=JobQueue(db))
    service.start()
    try:
        Event().wait()
    except KeyboardInterrupt:
        service.shutdown()
