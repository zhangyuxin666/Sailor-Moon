"""Read-only smoke check for a running Spring Boot business backend."""

from __future__ import annotations

import argparse
import json
import urllib.request


def get(base_url: str, path: str):
    with urllib.request.urlopen(base_url.rstrip("/") + path, timeout=5) as response:
        return response.status, response.headers.get_content_type(), response.read()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    arguments = parser.parse_args()

    status, _, body = get(arguments.base_url, "/health/ready")
    assert status == 200 and json.loads(body)["status"] == "ready"

    status, _, body = get(arguments.base_url, "/auth/status")
    auth = json.loads(body)
    assert status == 200 and "initialized" in auth and "account" in auth

    status, content_type, body = get(arguments.base_url, "/login")
    assert status == 200 and content_type == "text/html" and "活动管家" in body.decode("utf-8")
    print("SMOKE TEST PASSED")
