import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timedelta

from fastapi import Request

from .exceptions import PermissionDeniedError
from ..config import settings

SESSION_COOKIE = "activity_session"


def hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310_000)
    return f"pbkdf2_sha256${salt.hex()}${digest.hex()}"


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, salt_hex, expected_hex = encoded.split("$", 2)
        if algorithm != "pbkdf2_sha256":
            return False
        actual = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), 310_000
        )
        return hmac.compare_digest(actual.hex(), expected_hex)
    except (ValueError, TypeError):
        return False


def create_account(conn, username: str, display_name: str, password: str, role: str, student_no: str = "") -> dict:
    account_id = uuid.uuid4().hex[:16]
    now = datetime.now().isoformat()
    conn.execute(
        "INSERT INTO accounts (id, username, display_name, student_no, password_hash, role, status, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, 'active', ?)",
        (account_id, username.strip(), display_name.strip(), student_no.strip(), hash_password(password), role, now),
    )
    return {"id": account_id, "username": username, "display_name": display_name, "role": role}


def create_session(conn, account_id: str) -> str:
    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    now = datetime.now()
    conn.execute(
        "INSERT INTO auth_sessions (token_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        (token_hash, account_id, (now + timedelta(days=settings.session_days)).isoformat(), now.isoformat()),
    )
    return token


def get_current_account(db, request: Request, required: bool = True):
    token = request.cookies.get(SESSION_COOKIE, "")
    if not token:
        if required:
            raise PermissionDeniedError("请先登录")
        return None
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    with db.connect() as conn:
        row = conn.execute(
            "SELECT a.*, COALESCE(sec.force_password_change, 0) AS force_password_change "
            "FROM auth_sessions s JOIN accounts a ON a.id = s.account_id "
            "LEFT JOIN account_security sec ON sec.account_id = a.id "
            "WHERE s.token_hash = ? AND s.expires_at > ? AND a.status = 'active'",
            (token_hash, datetime.now().isoformat()),
        ).fetchone()
    if not row and required:
        raise PermissionDeniedError("登录已过期，请重新登录")
    return dict(row) if row else None


def require_manager(account: dict):
    if account["role"] != "manager":
        raise PermissionDeniedError("只有管理者可以执行此操作")


def set_password(conn, account_id: str, password: str, force_change: bool = False):
    now = datetime.now().isoformat()
    conn.execute(
        "UPDATE accounts SET password_hash = ? WHERE id = ?",
        (hash_password(password), account_id),
    )
    if conn.execute(
        "SELECT 1 FROM account_security WHERE account_id = ?", (account_id,)
    ).fetchone():
        conn.execute(
            "UPDATE account_security SET force_password_change = ?, failed_logins = 0, locked_until = NULL, "
            "password_changed_at = ? WHERE account_id = ?",
            (1 if force_change else 0, now, account_id),
        )
    else:
        conn.execute(
            "INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) "
            "VALUES (?, ?, 0, ?)",
            (account_id, 1 if force_change else 0, now),
        )
