import hashlib
import math
import re

import httpx
import psycopg
from psycopg.rows import dict_row

from .config import settings


class Embeddings:
    def profile(self) -> str:
        if settings.embedding_base_url and settings.embedding_api_key:
            value=settings.embedding_base_url.rstrip('/')+"|"+settings.embedding_model+"|"+str(settings.embedding_dimensions)
            return "remote:"+hashlib.sha256(value.encode()).hexdigest()[:32]
        return "local_hash_v1:"+str(settings.embedding_dimensions)

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

    def create_many(self, texts: list[str]) -> list[list[float]]:
        """Generate embeddings for multiple document chunks in one operation."""
        if not texts:
            return []

        if settings.embedding_base_url and settings.embedding_api_key:
            response = httpx.post(
                f"{settings.embedding_base_url.rstrip('/')}/embeddings",
                headers={"Authorization": f"Bearer {settings.embedding_api_key}"},
                json={
                    "model": settings.embedding_model,
                    "input": texts,
                    "dimensions": settings.embedding_dimensions,
                },
                timeout=30,
            )
            response.raise_for_status()

            data = sorted(
                response.json()["data"],
                key=lambda item: item.get("index", 0),
            )
            vectors = [item["embedding"] for item in data]

            if len(vectors) != len(texts):
                raise ValueError("Embedding result count does not match text count")
            if any(len(vector) != settings.embedding_dimensions for vector in vectors):
                raise ValueError("Embedding result has an invalid dimension")

            return vectors

        return [self._local_hash_embedding(text) for text in texts]

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
                    "WITH candidates AS ("
                    " SELECT d.id::text AS id,d.source,d.version,c.content,c.chunk_index,"
                    " d.metadata_json AS metadata,c.embedding AS candidate_embedding"
                    " FROM rag_document_chunks c JOIN rag_documents d ON d.id=c.document_id"
                    " WHERE (d.organization_id=%s OR d.organization_id IS NULL)"
                    " AND c.embedding_profile=%s AND c.document_version=d.version"
                    " UNION ALL"
                    " SELECT d.id::text,d.source,d.version,d.content,-1,d.metadata_json,d.embedding"
                    " FROM rag_documents d"
                    " WHERE (d.organization_id=%s OR d.organization_id IS NULL) AND d.embedding_profile=%s"
                    " AND NOT EXISTS (SELECT 1 FROM rag_document_chunks c WHERE c.document_id=d.id AND c.document_version=d.version)"
                    ") SELECT id,source,version,content,chunk_index,metadata,"
                    " 1-(candidate_embedding <=> %s::vector) AS score FROM candidates"
                    " ORDER BY candidate_embedding <=> %s::vector LIMIT %s",
                    (organization_id, self.embeddings.profile(), organization_id,
                     self.embeddings.profile(), embedding, embedding, top_k),
                )
            else:
                cursor.execute(
                    "WITH candidates AS ("
                    " SELECT d.id::text AS id,d.source,d.version,c.content,c.chunk_index,"
                    " d.metadata_json AS metadata,c.embedding AS candidate_embedding"
                    " FROM rag_document_chunks c JOIN rag_documents d ON d.id=c.document_id"
                    " WHERE d.organization_id IS NULL AND c.embedding_profile=%s AND c.document_version=d.version"
                    " UNION ALL"
                    " SELECT d.id::text,d.source,d.version,d.content,-1,d.metadata_json,d.embedding"
                    " FROM rag_documents d WHERE d.organization_id IS NULL AND d.embedding_profile=%s"
                    " AND NOT EXISTS (SELECT 1 FROM rag_document_chunks c WHERE c.document_id=d.id AND c.document_version=d.version)"
                    ") SELECT id,source,version,content,chunk_index,metadata,"
                    " 1-(candidate_embedding <=> %s::vector) AS score FROM candidates"
                    " ORDER BY candidate_embedding <=> %s::vector LIMIT %s",
                    (self.embeddings.profile(), self.embeddings.profile(), embedding, embedding, top_k),
                )
            return list(cursor.fetchall())

    def retrieve(self, organization_id: str, query: str) -> tuple[str,dict]:
        profile=self.embeddings.profile()
        try:
            results=[item for item in self.search(organization_id or None,query,4) if float(item.get("score") or 0)>0.05]
        except Exception:
            return "",{"retrieval_status":"unavailable","sources":[],"embedding_profile":profile}
        sources=[{"id":item["id"],"source":item["source"],"version":item["version"],"chunk_index":item.get("chunk_index",-1),"excerpt":item["content"][:240],"score":float(item.get("score") or 0)} for item in results]
        context="\n\n".join(f"[资料 {item['id']} v{item['version']}：{item['source']}]\n{item['content'][:1800]}" for item in results)
        return context,{"retrieval_status":"used" if sources else "empty","sources":sources,"embedding_profile":profile}

    def context(self,organization_id: str,query: str) -> str:
        return self.retrieve(organization_id,query)[0]
