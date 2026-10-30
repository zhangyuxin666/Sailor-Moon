import re

def _normalize_text(content: str) -> str:
    """整理文档文本，减少无意义的空格和空行。"""

    # 把 Windows 和旧版 Mac 的换行符统一成 \n
    text = content.replace("\r\n", "\n").replace("\r", "\n")

    # 清除每一行开头和结尾的空格
    lines = [line.strip() for line in text.split("\n")]

    # 连续三个及以上的换行，压缩成两个换行
    text = "\n".join(lines)
    text = re.sub(r"\n{3,}", "\n\n", text)

    # 清除整篇文档开头和结尾的空白
    return text.strip()

def split_document(
    content: str,
    chunk_size: int = 500,
    overlap: int = 80,
) -> list[str]:
    """
    把一篇长文档切分成多个文本片段。

    参数：
        content: 完整的文档内容。
        chunk_size: 每个片段允许的最大字符数。
        overlap: 相邻片段之间重复保留的字符数。

    返回：
        文本片段列表。
    """
    # 文档内容必须是字符串
    if not isinstance(content, str):
        raise TypeError("content 必须是字符串")

    # 每个片段必须至少允许一个字符
    if chunk_size <= 0:
        raise ValueError("chunk_size 必须大于 0")

    # 重叠长度不能是负数
    if overlap < 0:
        raise ValueError("overlap 不能小于 0")

    # 重叠长度必须小于片段长度，否则切分位置无法向前移动
   
    if overlap >= chunk_size:
        raise ValueError("overlap 必须小于 chunk_size")
        # 先清理文本中的多余空格和空行
    text = _normalize_text(content)

    # 空文档不需要切分
    if not text:
        return []

    # 文档没有超过限制时，直接作为一个片段返回
    if len(text) <= chunk_size:
        return [text]
        # 保存最终生成的全部片段
    chunks: list[str] = []

    # 当前片段在原文中的起始位置
    start = 0

    while start < len(text):
        # 先按照 chunk_size 计算一个暂定结束位置
        end = min(start + chunk_size, len(text))

        # 如果还没有到文档末尾，尽量在完整句子处结束
        if end < len(text):
            # 只在片段后半部分寻找句子边界，
            # 避免为了找到标点而生成过短的片段
            search_start = start + chunk_size // 2
            candidate_text = text[search_start:end]

            # 支持中文标点、英文标点和换行
            boundary_positions = [
                candidate_text.rfind(separator)
                for separator in ("\n", "。", "！", "？", "；", ".", "!", "?", ";")
            ]

            # 找出最靠后的句子边界
            best_boundary = max(boundary_positions)

            # 找到了合适的标点，就在标点之后切分
            if best_boundary != -1:
                end = search_start + best_boundary + 1
                       # 取出当前片段，并清除片段两端空白
        chunk = text[start:end].strip()

        # 避免把空字符串保存到结果中
        if chunk:
            chunks.append(chunk)

        # 已经到达文档末尾，结束循环
        if end >= len(text):
            break

        # 下一段向前回退 overlap 个字符，保留上下文联系
        next_start = end - overlap

        # 安全保护：确保切分位置每次至少向前移动一个字符
        start = max(next_start, start + 1)

    return chunks 
