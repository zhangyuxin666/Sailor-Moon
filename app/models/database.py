import sqlite3
from contextlib import contextmanager

SCHEMA = """
CREATE TABLE IF NOT EXISTS activities (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL,           -- 创建者，权限控制的依据
    title       TEXT NOT NULL,
    raw_input   TEXT NOT NULL,           -- 用户的一句话原始输入
    plan_json   TEXT,                    -- LLM 生成的策划 JSON
    status      TEXT NOT NULL DEFAULT 'planned',
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS steps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    activity_id TEXT NOT NULL,
    step        TEXT NOT NULL,           -- 编排器步骤名
    status      TEXT NOT NULL,           -- done / failed
    detail      TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id          TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL,
    title       TEXT NOT NULL,
    assignee    TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'pending',
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS forms (
    id          TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL,
    title       TEXT NOT NULL,
    fields_json TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS registrations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    form_id     TEXT NOT NULL,
    name        TEXT NOT NULL,
    contact     TEXT NOT NULL,
    extra_json  TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reminders (
    id          TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL,
    message     TEXT NOT NULL,
    remind_at   TEXT NOT NULL,           -- ISO 时间
    status      TEXT NOT NULL DEFAULT 'scheduled',  -- scheduled / sent / cancelled
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    activity_id TEXT,
    recipient   TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key         TEXT PRIMARY KEY,
    tool_name   TEXT NOT NULL,
    result_json TEXT NOT NULL,           -- 首次执行结果的缓存，命中直接返回
    created_at  TEXT NOT NULL
);
"""


class Database:
    """SQLite 轻量封装：每次操作一个连接，事务自动提交/回滚。"""

    def __init__(self, path: str = "activity.db"):
        self.path = path
        self.init()

    @contextmanager
    def connect(self):
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def init(self):
        with self.connect() as conn:
            conn.executescript(SCHEMA)
