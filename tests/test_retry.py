import pytest

from app.core.exceptions import ToolTransientError
from app.core.retry import retry


def test_retry_succeeds_after_transient_failures():
    calls = {"n": 0}

    @retry(max_attempts=3, base_delay=0)
    def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            raise ToolTransientError("网络抖动")
        return "ok"

    assert flaky() == "ok"
    assert calls["n"] == 3


def test_retry_gives_up_after_max_attempts():
    calls = {"n": 0}

    @retry(max_attempts=2, base_delay=0)
    def always_fail():
        calls["n"] += 1
        raise ToolTransientError("一直失败")

    with pytest.raises(ToolTransientError):
        always_fail()
    assert calls["n"] == 2


def test_non_retryable_error_is_not_retried():
    calls = {"n": 0}

    @retry(max_attempts=3, base_delay=0)
    def bad():
        calls["n"] += 1
        raise ValueError("不可重试")

    with pytest.raises(ValueError):
        bad()
    assert calls["n"] == 1
