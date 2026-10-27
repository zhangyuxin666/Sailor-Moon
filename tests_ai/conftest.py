"""All AI tests use local fixtures, never real model, embedding or database services."""
import httpx
import psycopg
import pytest

from ai_service.config import settings


@pytest.fixture(autouse=True)
def offline_services(monkeypatch):
    monkeypatch.setattr(settings, "llm_api_key", "")
    monkeypatch.setattr(settings, "embedding_api_key", "")
    monkeypatch.setattr(settings, "embedding_base_url", "")

    def reject_external(*args, **kwargs):
        raise AssertionError("Tests must use a local service fixture")

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", reject_external)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", reject_external)
    monkeypatch.setattr(psycopg, "connect", reject_external)
