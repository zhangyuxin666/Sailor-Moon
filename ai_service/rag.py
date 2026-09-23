import hashlib
import math
import re

import httpx
import psycopg
from psycopg.rows import dict_row

from .config import settings


class Embeddings:
    def create(self, text: str) -> list[float]:
        if settings.embedding_base_url and settings.embedding_api_key:
            response = httpx.post(
                f"{settings.embedding_base_url.rstrip('/')}/embeddings",
                headers={"Authorization": f"Bearer {settings.embedding_api_key}"},
                json={
                    "model": settings.embedding_model,
                    "input": text,
                    "dimensions": settings.embedding_dimensions,
                },
                timeout=30,
            )
            response.raise_for_status()
            return response.json()["data"][0]["embedding"]
        return self._local_hash_embedding(text)

    def _local_hash_embedding(self, text: str) -> list[float]:
        # An offline deterministic fallback. Production should configure a real
        # embedding endpoint; keeping the dimensions stable preserves pgvector data.
        normalized = re.sub(r"\s+", " ", text.lower()).strip()
        tokens = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", normalized)
        tokens += [normalized[index:index + 2] for index in range(max(0, len(normalized) - 1))]
        vector = [0.0] * settings.embedding_dimensions
        for token in tokens:
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % len(vector)
            vector[index] += 1.0 if digest[4] & 1 else -1.0
        norm = math.sqrt(sum(value * value for value in vector)) or 1.0
        return [value / norm for value in vector]


class RagStore:
    def __init__(self) -> None:
        self.embeddings = Embeddings()

    def _connect(self):
        url = settings.database_url.replace("postgresql+psycopg://", "postgresql://")
        return psycopg.connect(url, row_factory=dict_row)

    @staticmethod
    def _vector(values: list[float]) -> str:
        return "[" + ",".join(f"{value:.9f}" for value in values) + "]"

    def search(self, organization_id: str | None, query: str, top_k: int = 5) -> list[dict]:
        embedding = self._vector(self.embeddings.create(query))
        with self._connect() as connection, connection.cursor() as cursor:
            if organization_id:
                cursor.execute(
                    "SELECT id::text, source, content, metadata_json AS metadata, "
                    "1 - (embedding <=> %s::vector) AS score "
                    "FROM rag_documents "
                    "WHERE organization_id = %s OR organization_id IS NULL "
                    "ORDER BY embedding <=> %s::vector LIMIT %s",
                    (embedding, organization_id, embedding, top_k),
                )
            else:
                cursor.execute(
                    "SELECT id::text, source, content, metadata_json AS metadata, "
                    "1 - (embedding <=> %s::vector) AS score "
                    "FROM rag_documents WHERE organization_id IS NULL "
                    "ORDER BY embedding <=> %s::vector LIMIT %s",
                    (embedding, embedding, top_k),
                )
            return list(cursor.fetchall())

    def context(self, organization_id: str, query: str) -> str:
        try:
            results = self.search(organization_id or None, query, 4)
        except Exception:
            return ""
        return "\n\n".join(
            f"[知识库：{item['source']}]\n{item['content'][:1800]}" for item in results
            if float(item.get("score") or 0) > 0.05
        )
