import json
import uuid
from datetime import datetime


def write_audit(
    conn,
    action: str,
    account_id: str | None = None,
    organization_id: str | None = None,
    resource_type: str | None = None,
    resource_id: str | None = None,
    detail: dict | None = None,
    ip_address: str | None = None,
):
    conn.execute(
        "INSERT INTO audit_logs (id, organization_id, account_id, action, resource_type, resource_id, detail_json, ip_address, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            uuid.uuid4().hex[:16], organization_id, account_id, action,
            resource_type, resource_id,
            json.dumps(detail or {}, ensure_ascii=False), ip_address,
            datetime.now().isoformat(),
        ),
    )
