from .exceptions import NotFoundError, PermissionDeniedError


def check_activity_owner(conn, activity_id: str, user_id: str) -> None:
    """校验 user_id 是否为活动创建者，不是则抛 PermissionDeniedError。"""
    row = conn.execute(
        "SELECT user_id FROM activities WHERE id = ?", (activity_id,)
    ).fetchone()
    if not row:
        raise NotFoundError(f"活动不存在: {activity_id}")
    if row["user_id"] != user_id:
        raise PermissionDeniedError("只能操作自己创建的活动")
