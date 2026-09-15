class ToolError(Exception):
    """工具执行失败（不可重试）。"""


class ToolTransientError(ToolError):
    """工具执行失败（瞬时错误，可重试，如网络抖动、限流）。"""


class PermissionDeniedError(Exception):
    """越权操作：只能操作自己创建的活动。"""


class NotFoundError(Exception):
    """资源不存在。"""
