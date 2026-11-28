from ai_service.rag import RagStore


class FakeCursor:
    def __init__(self, rows):
        self.rows = rows
        self.sql = ""
        self.params = ()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, sql, params):
        self.sql = sql
        self.params = params

    def fetchall(self):
        return self.rows


class FakeConnection:
    def __init__(self, cursor):
        self.value = cursor

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def cursor(self):
        return self.value


def test_search_uses_chunks_and_server_supplied_organization(monkeypatch):
    cursor = FakeCursor([{
        "id": "doc-1", "source": "活动规范", "version": 2,
        "content": "签到需要准备签到表。", "chunk_index": 1,
        "metadata": {}, "score": 0.82,
    }])
    store = RagStore()
    monkeypatch.setattr(store, "_connect", lambda: FakeConnection(cursor))
    monkeypatch.setattr(store.embeddings, "create", lambda _: [0.0] * 1536)

    rows = store.search("org-1", "签到准备", 5)

    assert rows[0]["chunk_index"] == 1
    assert "FROM rag_document_chunks c JOIN rag_documents d" in cursor.sql
    assert "c.document_version=d.version" in cursor.sql
    assert "d.organization_id=%s OR d.organization_id IS NULL" in cursor.sql
    assert cursor.params[0] == "org-1"
    assert cursor.params[2] == "org-1"
    assert cursor.params[-1] == 5


def test_public_search_excludes_private_documents(monkeypatch):
    cursor = FakeCursor([])
    store = RagStore()
    monkeypatch.setattr(store, "_connect", lambda: FakeConnection(cursor))
    monkeypatch.setattr(store.embeddings, "create", lambda _: [0.0] * 1536)

    store.search(None, "公共流程", 3)

    assert "d.organization_id IS NULL" in cursor.sql
    assert "d.organization_id=%s" not in cursor.sql
    assert cursor.params[-1] == 3


def test_retrieve_exposes_chunk_index_in_citation(monkeypatch):
    store = RagStore()
    monkeypatch.setattr(store, "search", lambda *_: [{
        "id": "doc-1", "source": "活动规范", "version": 3,
        "content": "活动前一天检查设备。", "chunk_index": 2,
        "score": 0.9,
    }])

    context, evidence = store.retrieve("org-1", "设备")

    assert "活动前一天检查设备" in context
    assert evidence["retrieval_status"] == "used"
    assert evidence["sources"][0]["chunk_index"] == 2
