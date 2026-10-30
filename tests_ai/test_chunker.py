import pytest

from ai_service.chunker import split_document
def test_empty_document_returns_empty_list():
    """空文档不应该生成任何片段。"""

    result = split_document("")

    assert result == []
def test_short_document_is_not_split():
    """没有超过最大长度的文档应该保持为一个片段。"""

    result = split_document("  活动开始前请签到。  ")

    assert result == ["活动开始前请签到。"]
def test_long_document_is_split():
    """超过最大长度的文档应该被切成多个片段。"""

    content = "A" * 260

    result = split_document(
        content,
        chunk_size=100,
        overlap=20,
    )

    assert len(result) == 3
    assert all(len(chunk) <= 100 for chunk in result)
def test_adjacent_chunks_keep_overlap():
    """相邻片段之间应该保留指定长度的重复内容。"""

    content = "".join(str(index % 10) for index in range(260))

    result = split_document(
        content,
        chunk_size=100,
        overlap=20,
    )

    assert result[0][-20:] == result[1][:20]
    assert result[1][-20:] == result[2][:20]

def test_document_prefers_sentence_boundary():
    """后半段存在句号时，应该优先从句号后切分。"""

    content = "甲" * 60 + "。" + "乙" * 80

    result = split_document(
        content,
        chunk_size=100,
        overlap=20,
    )

    assert len(result) == 2
    assert result[0] == "甲" * 60 + "。"
    assert result[0].endswith("。") 
    
def test_invalid_parameters_raise_errors():
    """不合法的切分参数应该主动抛出异常。"""

    with pytest.raises(ValueError):
        split_document("活动资料", chunk_size=0, overlap=0)

    with pytest.raises(ValueError):
        split_document("活动资料", chunk_size=100, overlap=-1)

    with pytest.raises(ValueError):
        split_document("活动资料", chunk_size=100, overlap=100)

    with pytest.raises(TypeError):
        split_document(None, chunk_size=100, overlap=20)   