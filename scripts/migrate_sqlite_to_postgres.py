"""One-time, non-destructive copy from the legacy SQLite database to PostgreSQL.

Run the Spring service once first so Flyway creates the target schema, then:

    python scripts/migrate_sqlite_to_postgres.py activity.db \
      postgresql://activity:activity@127.0.0.1:55432/activity

Existing PostgreSQL rows are preserved (`ON CONFLICT DO NOTHING`).
"""

from __future__ import annotations

import argparse
import re
import sqlite3
from pathlib import Path

import psycopg

IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def quoted(name: str) -> str:
    if not IDENTIFIER.fullmatch(name):
        raise ValueError(f"Unsafe identifier: {name!r}")
    return f'"{name}"'


def migrate(sqlite_path: Path, postgres_url: str) -> None:
    if not sqlite_path.is_file():
        raise FileNotFoundError(sqlite_path)
    postgres_url = postgres_url.replace("postgresql+psycopg://", "postgresql://")
    with sqlite3.connect(sqlite_path) as source, psycopg.connect(postgres_url) as target:
        source.row_factory = sqlite3.Row
        tables = [
            row[0]
            for row in source.execute(
                "SELECT name FROM sqlite_master WHERE type='table' "
                "AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
        ]
        with target.cursor() as cursor:
            for table in tables:
                cursor.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position",
                    (table,),
                )
                target_columns = {row[0] for row in cursor.fetchall()}
                if not target_columns:
                    print(f"skip {table}: target table does not exist")
                    continue
                source_columns = [row[1] for row in source.execute(f"PRAGMA table_info({quoted(table)})")]
                columns = [column for column in source_columns if column in target_columns]
                rows = source.execute(
                    f"SELECT {','.join(quoted(column) for column in columns)} FROM {quoted(table)}"
                ).fetchall()
                if not rows:
                    print(f"copy {table}: 0 rows")
                    continue
                statement = (
                    f"INSERT INTO {quoted(table)} ({','.join(quoted(column) for column in columns)}) "
                    f"VALUES ({','.join(['%s'] * len(columns))}) ON CONFLICT DO NOTHING"
                )
                cursor.executemany(statement, [tuple(row[column] for column in columns) for row in rows])
                print(f"copy {table}: {len(rows)} rows")

            for table in ("steps", "messages", "registrations"):
                cursor.execute("SELECT pg_get_serial_sequence(%s, 'id')", (table,))
                sequence = cursor.fetchone()[0]
                if not sequence:
                    continue
                cursor.execute(f"SELECT COALESCE(MAX(id), 0) FROM {quoted(table)}")
                maximum = int(cursor.fetchone()[0])
                cursor.execute("SELECT setval(%s, %s, %s)", (sequence, max(maximum, 1), maximum > 0))
        target.commit()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sqlite_path", type=Path)
    parser.add_argument("postgres_url")
    arguments = parser.parse_args()
    migrate(arguments.sqlite_path.resolve(), arguments.postgres_url)
