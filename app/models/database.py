import re
import uuid
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
qq_bindings = Table("qq_bindings", metadata,
    Column("group_openid", String, primary_key=True), Column("user_id", String, nullable=False, index=True),
    Column("group_label", String), Column("status", String, nullable=False, default="active"),
    Column("last_seen_at", String, nullable=False), Column("created_at", String, nullable=False))
qq_binding_codes = Table("qq_binding_codes", metadata,
    Column("code", String, primary_key=True), Column("user_id", String, nullable=False, index=True),
    Column("expires_at", String, nullable=False), Column("created_at", String, nullable=False))
activity_channels = Table("activity_channels", metadata,
    Column("activity_id", String, primary_key=True), Column("user_id", String, nullable=False, index=True),
    Column("group_openid", String, nullable=False, index=True), Column("created_at", String, nullable=False))
activity_classes = Table("activity_classes", metadata,
    Column("activity_id", String, primary_key=True), Column("class_id", String, nullable=False, index=True),
    Column("organization_id", String, nullable=False, index=True))
activity_task_assignees = Table("activity_task_assignees", metadata,
    Column("task_id", String, primary_key=True), Column("account_id", String, nullable=False, index=True))
activity_workflows = Table("activity_workflows", metadata,
    Column("activity_id", String, primary_key=True), Column("config_json", Text, nullable=False))
agent_drafts = Table("agent_drafts", metadata,
    Column("id", String, primary_key=True), Column("organization_id", String, nullable=False, index=True),
    Column("class_id", String, nullable=False, index=True), Column("creator_id", String, nullable=False, index=True),
    Column("raw_input", Text, nullable=False), Column("intent_type", String, nullable=False),
    Column("complexity", String, nullable=False), Column("draft_json", Text, nullable=False),
    Column("status", String, nullable=False, default="draft"), Column("result_type", String),
    Column("result_id", String), Column("created_at", String, nullable=False), Column("updated_at", String, nullable=False))
delivery_events = Table("delivery_events", metadata,
    Column("id", String, primary_key=True), Column("activity_id", String, nullable=False, index=True),
    Column("kind", String, nullable=False), Column("channel", String, nullable=False),
    Column("target", String, nullable=False), Column("content", Text, nullable=False),
    Column("status", String, nullable=False, index=True), Column("external_id", String),
    Column("error", Text), Column("created_at", String, nullable=False), Column("completed_at", String))
qq_inbound_events = Table("qq_inbound_events", metadata,
    Column("message_id", String, primary_key=True), Column("group_openid", String, nullable=False, index=True),
    Column("sender_openid", String), Column("sender_name", String), Column("content", Text, nullable=False),
    Column("created_at", String, nullable=False))
integration_state = Table("integration_state", metadata,
    Column("key", String, primary_key=True), Column("value_json", Text, nullable=False),
    Column("updated_at", String, nullable=False))
accounts = Table("accounts", metadata,
    Column("id", String, primary_key=True), Column("username", String, nullable=False, unique=True, index=True),
    Column("display_name", String, nullable=False), Column("student_no", String),
    Column("password_hash", Text, nullable=False), Column("role", String, nullable=False),
    Column("status", String, nullable=False, default="active"), Column("created_at", String, nullable=False))
auth_sessions = Table("auth_sessions", metadata,
    Column("token_hash", String, primary_key=True), Column("account_id", String, nullable=False, index=True),
    Column("expires_at", String, nullable=False), Column("created_at", String, nullable=False))
classes = Table("classes", metadata,
    Column("id", String, primary_key=True), Column("name", String, nullable=False),
    Column("manager_id", String, nullable=False, index=True), Column("qq_group_openid", String),
    Column("created_at", String, nullable=False))
class_members = Table("class_members", metadata,
    Column("class_id", String, primary_key=True), Column("account_id", String, primary_key=True),
    Column("created_at", String, nullable=False))
todos = Table("todos", metadata,
    Column("id", String, primary_key=True), Column("class_id", String, nullable=False, index=True),
    Column("creator_id", String, nullable=False), Column("title", String, nullable=False),
    Column("description", Text), Column("kind", String, nullable=False, default="homework"),
    Column("deadline", String, nullable=False, index=True), Column("status", String, nullable=False, default="open"),
    Column("created_at", String, nullable=False))
todo_assignees = Table("todo_assignees", metadata,
    Column("todo_id", String, primary_key=True), Column("account_id", String, primary_key=True),
    Column("status", String, nullable=False, default="pending"), Column("note", Text),
    Column("file_path", Text), Column("original_filename", String),
    Column("submitted_at", String), Column("updated_at", String, nullable=False))
todo_reminders = Table("todo_reminders", metadata,
    Column("id", String, primary_key=True), Column("todo_id", String, nullable=False, index=True),
    Column("remind_at", String, nullable=False, index=True), Column("status", String, nullable=False, default="scheduled"),
    Column("created_at", String, nullable=False))
classroom_deliveries = Table("classroom_deliveries", metadata,
    Column("id", String, primary_key=True), Column("todo_id", String, nullable=False, index=True),
    Column("kind", String, nullable=False), Column("content", Text, nullable=False),
    Column("status", String, nullable=False), Column("external_id", String), Column("error", Text),
    Column("created_at", String, nullable=False))
organizations = Table("organizations", metadata,
    Column("id", String, primary_key=True), Column("name", String, nullable=False),
    Column("owner_account_id", String, nullable=False, index=True), Column("created_at", String, nullable=False))
organization_members = Table("organization_members", metadata,
    Column("organization_id", String, primary_key=True), Column("account_id", String, primary_key=True),
    Column("role", String, nullable=False), Column("created_at", String, nullable=False))
class_organizations = Table("class_organizations", metadata,
    Column("class_id", String, primary_key=True), Column("organization_id", String, nullable=False, index=True))
account_security = Table("account_security", metadata,
    Column("account_id", String, primary_key=True), Column("force_password_change", Integer, nullable=False, default=0),
    Column("failed_logins", Integer, nullable=False, default=0), Column("locked_until", String),
    Column("password_changed_at", String, nullable=False))
privacy_consents = Table("privacy_consents", metadata,
    Column("account_id", String, primary_key=True), Column("policy_version", String, nullable=False),
    Column("accepted_at", String, nullable=False), Column("ip_address", String))
audit_logs = Table("audit_logs", metadata,
    Column("id", String, primary_key=True), Column("organization_id", String, index=True),
    Column("account_id", String, index=True), Column("action", String, nullable=False, index=True),
    Column("resource_type", String), Column("resource_id", String), Column("detail_json", Text),
    Column("ip_address", String), Column("created_at", String, nullable=False))
file_assets = Table("file_assets", metadata,
    Column("id", String, primary_key=True), Column("todo_id", String, nullable=False, index=True),
    Column("account_id", String, nullable=False, index=True), Column("storage_key", Text, nullable=False),
    Column("original_filename", String, nullable=False), Column("content_type", String),
    Column("size_bytes", Integer, nullable=False), Column("created_at", String, nullable=False))
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
        self._migrate_existing_data()

    def _migrate_existing_data(self):
        now = __import__("datetime").datetime.now().isoformat()
        with self.connect() as conn:
            accounts_rows = conn.execute("SELECT id, username, role, created_at FROM accounts").fetchall()
            for account in accounts_rows:
                if not conn.execute(
                    "SELECT 1 FROM account_security WHERE account_id = ?", (account["id"],)
                ).fetchone():
                    conn.execute(
                        "INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) "
                        "VALUES (?, 0, 0, ?)",
                        (account["id"], account["created_at"] or now),
                    )
            managers = [row for row in accounts_rows if row["role"] == "manager"]
            for manager in managers:
                membership = conn.execute(
                    "SELECT organization_id FROM organization_members WHERE account_id = ? LIMIT 1",
                    (manager["id"],),
                ).fetchone()
                if membership:
                    organization_id = membership["organization_id"]
                else:
                    organization_id = uuid.uuid4().hex[:12]
                    conn.execute(
                        "INSERT INTO organizations (id, name, owner_account_id, created_at) VALUES (?, ?, ?, ?)",
                        (organization_id, f"{manager['username']} 的组织", manager["id"], now),
                    )
                    conn.execute(
                        "INSERT INTO organization_members (organization_id, account_id, role, created_at) "
                        "VALUES (?, ?, 'owner', ?)",
                        (organization_id, manager["id"], now),
                    )
                classes_rows = conn.execute(
                    "SELECT id FROM classes WHERE manager_id = ?", (manager["id"],)
                ).fetchall()
                for classroom in classes_rows:
                    if not conn.execute(
                        "SELECT 1 FROM class_organizations WHERE class_id = ?", (classroom["id"],)
                    ).fetchone():
                        conn.execute(
                            "INSERT INTO class_organizations (class_id, organization_id) VALUES (?, ?)",
                            (classroom["id"], organization_id),
                        )
