import functools
import time

from .exceptions import ToolTransientError


def retry(max_attempts: int = 3, base_delay: float = 0.2, retry_on=(ToolTransientError,)):
    """指数退避重试装饰器。只重试 retry_on 指定的可重试异常，其余异常直接抛出。"""

    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            delay = base_delay
            for attempt in range(1, max_attempts + 1):
                try:
                    return fn(*args, **kwargs)
                except retry_on:
                    if attempt == max_attempts:
                        raise
                    time.sleep(delay)
                    delay *= 2

        return wrapper

    return deco
