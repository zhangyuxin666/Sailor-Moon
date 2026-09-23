import hmac

import psycopg
from fastapi import Depends, FastAPI, Header, HTTPException

from .config import settings
from .llm import AiEngine
from .models import (EmbedRequest, PlanRequest, RagSearchRequest, RecapRequest,
                     ReplyRequest, WorkRequest)
from .rag import RagStore

app = FastAPI(
    title="Activity Assistant AI Service",
    description="Internal-only AI, Agent reasoning, and pgvector RAG service",
    docs_url=None,
    redoc_url=None,
)
engine = AiEngine()
rag = RagStore()


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    if not hmac.compare_digest(x_internal_token, settings.ai_internal_token):
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/health")
def health():
    return {"status": "ok", "service": "ai"}


@app.get("/health/ready")
def ready():
    url = settings.database_url.replace("postgresql+psycopg://", "postgresql://")
    with psycopg.connect(url) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT 1")
        cursor.fetchone()
    return {"status": "ready", "database": "ok", "rag": "pgvector"}


@app.post("/internal/ai/plan", dependencies=[Depends(require_internal_token)])
def plan(body: PlanRequest):
    context = rag.context(body.organization_id, body.raw_input)
    return engine.plan(body.raw_input, body.available_assignees, context).model_dump()


@app.post("/internal/ai/analyze-work-request", dependencies=[Depends(require_internal_token)])
def analyze(body: WorkRequest):
    context = rag.context(body.organization_id, body.raw_input)
    return engine.analyze(body.raw_input, body.class_context, context).model_dump()


@app.post("/internal/ai/recap", dependencies=[Depends(require_internal_token)])
def recap(body: RecapRequest):
    context = rag.context(body.organization_id, body.activity_title)
    return {"recap": engine.recap(body.activity_title, body.stats, body.task_summary, context)}


@app.post("/internal/ai/reply", dependencies=[Depends(require_internal_token)])
def reply(body: ReplyRequest):
    context = rag.context(body.organization_id, body.message)
    return {"reply": engine.reply(body.message, body.activity_context, context)}


@app.post("/internal/ai/embed", dependencies=[Depends(require_internal_token)])
def embed(body: EmbedRequest):
    return {"embedding": rag.embeddings.create(body.text)}


@app.post("/internal/rag/search", dependencies=[Depends(require_internal_token)])
def search(body: RagSearchRequest):
    results = rag.search(body.organization_id, body.query, body.top_k)
    return {"results": results}
