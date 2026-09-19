from .agent.llm import get_llm
from .config import settings
from .jobs import JobQueue, Worker
from .models.database import Database
from .scheduler.reminders import ReminderService
from .services.activity_service import ActivityService
from .services.classroom_service import ClassroomService


def build_worker():
    db = Database(settings.database_url)
    queue = JobQueue(db)
    reminders = ReminderService(db, queue=queue)
    service = ActivityService(db, get_llm(), reminders, queue=queue)
    classroom = ClassroomService(db, reminders=reminders)
    return Worker(queue, service, reminders, classroom_service=classroom)


if __name__ == "__main__":
    build_worker().run_forever()
