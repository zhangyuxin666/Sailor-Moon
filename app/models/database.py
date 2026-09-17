import re
from contextlib import contextmanager

from sqlalchemy import Column, Integer, MetaData, String, Table, Text, UniqueConstraint, create_engine, text

metadata = MetaData()

activities = Table("activities", metadata,
    Column("id", String, primary_key=True), Column("user_id", String, nullable=False, index=True),
    Column("title", String, nullable=False), Column("raw_input", Text, nullable=False),
    Column("plan_json", Text), Column("status", String, nullable=False, default="queued"),
    Column("created_at", String, nullable=False))
background_jobs = Table("background_jobs", metadata,
    Column("id", String, primary_key=True), Column("kind", String, nullable=False),
    Column("ref_id", String, nullable=False, index=True),
    Column("status", String, nullable=False, index=True), Column("attempts", Integer, nullable=False, default=0),
    Column("error", Text), Column("created_at", String, nullable=False), Column("started_at", String),
    Column("finished_at", String), UniqueConstraint("kind", "ref_id", name="uq_job_kind_ref"))
steps = Table("steps", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True), Column("activity_id", String, nullable=False, index=True),
    Column("step", String, nullable=False), Column("status", String, nullable=False), Column("detail", Text),
    Column("created_at", String, nullable=False))
tasks = Table("tasks", metadata,
    Column("id", String, primary_key=True), Column("activity_id", String, nullable=False, index=True),
    Column("title", String, nullable=False), Column("assignee", String, nullable=False),
    Column("status", String, nullable=False, default="pending"), Column("created_at", String, nullable=False))
forms = Table("forms", metadata,
    Column("id", String, primary_key=True), Column("activity_id", String, nullable=False, index=True),
    Column("title", String, nullable=False), Column("fields_json", Text, nullable=False), Column("created_at", String, nullable=False))
registrations = Table("registrations", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True), Column("form_id", String, nullable=False, index=True),
    Column("name", String, nullable=False), Column("contact", String, nullable=False),
    Column("extra_json", Text), Column("created_at", String, nullable=False))
reminders = Table("reminders", metadata,
    Column("id", String, primary_key=True), Column("activity_id", String, nullable=False, index=True),
    Column("message", Text, nullable=False), Column("remind_at", String, nullable=False, index=True),
    Column("status", String, nullable=False, default="scheduled"), Column("created_at", String, nullable=False),
    UniqueConstraint("activity_id", "remind_at", name="uq_reminder_activity_time"))
messages = Table("messages", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True), Column("activity_id", String, index=True),
    Column("recipient", String, nullable=False), Column("content", Text, nullable=False), Column("created_at", String, nullable=False))
idempotency_keys = Table("idempotency_keys", metadata,
    Column("key", String, primary_key=True), Column("tool_name", String, nullable=False),
    Column("result_json", Text, nullable=False), Column("created_at", String, nullable=False))

class ResultAdapter:
    def __init__(self, result):
        self._result = result
    def fetchone(self):
        return self._result.mappings().fetchone()
    def fetchall(self):
        return self._result.mappings().fetchall()

class ConnectionAdapter:
    """Keep the original qmark SQL API while SQLAlchemy adapts the database."""
    def __init__(self, connection):
        self._connection = connection
    def execute(self, sql: str, params=()):
        values = list(params or ())
        index = 0
        def replace(_match):
            nonlocal index
            name = f"p{index}"
            index += 1
            return f":{name}"
        statement = re.sub(r"\?", replace, sql)
        binds = {f"p{i}": value for i, value in enumerate(values)}
        return ResultAdapter(self._connection.execute(text(statement), binds))

class Database:
    """Unified database: SQLite for local/test and PostgreSQL for deployment."""
    def __init__(self, url: str = "sqlite:///activity.db"):
        if "://" not in url:
            url = f"sqlite:///{url}"
        connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
        self.url = url
        self.engine = create_engine(url, pool_pre_ping=True, connect_args=connect_args)
        self.init()
    @contextmanager
    def connect(self):
        with self.engine.begin() as connection:
            yield ConnectionAdapter(connection)
    def init(self):
        metadata.create_all(self.engine)
