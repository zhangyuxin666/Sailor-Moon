from fastapi.testclient import TestClient

from ai_service.chunker import split_document
from ai_service.config import settings
from ai_service.main import app


def test_chunk_embed_requires_internal_token():
    client = TestClient(app)

    response = client.post(
        "/internal/ai/chunk-embed",
        json={"content": "活动资料", "chunk_size": 100, "overlap": 20},
    )

    assert response.status_code == 401


def test_chunk_embed_returns_ordered_vectors_for_all_chunks():
    client = TestClient(app)
    content = "活动报名流程。" + "甲" * 100 + "活动费用报销。" + "乙" * 100

    response = client.post(
        "/internal/ai/chunk-embed",
        headers={"X-Internal-Token": settings.ai_internal_token},
        json={"content": content, "chunk_size": 100, "overlap": 20},
    )

    assert response.status_code == 200
    body = response.json()
    expected_chunks = split_document(content, chunk_size=100, overlap=20)

    assert body["embedding_profile"] == "local_hash_v1:1536"
    assert [item["chunk_index"] for item in body["chunks"]] == list(range(len(expected_chunks)))
    assert [item["content"] for item in body["chunks"]] == expected_chunks
    assert all(len(item["embedding"]) == 1536 for item in body["chunks"])
