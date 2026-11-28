#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 docs/SYSTEM_DESIGN.md 转成 Word 文档（不依赖 Microsoft Word）。

用法:
    python scripts/export-system-design-docx.py
    python scripts/export-system-design-docx.py --md docs/SYSTEM_DESIGN.md --out docs/活动管家系统设计说明书.docx
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parent.parent
CN_FONT = "微软雅黑"
CODE_FONT = "Consolas"
BODY_SIZE = Pt(10.5)
IMAGE_WIDTH = Cm(15.4)


def set_run_font(run, name=CN_FONT, size=None, bold=None, color=None):
    run.font.name = name
    run._element.rPr.rFonts.set(qn("w:eastAsia"), name)
    if size is not None:
        run.font.size = size
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = color


INLINE = re.compile(r"(\*\*[^*]+\*\*|`[^`]+`)")


def add_rich_text(paragraph, text: str, size=BODY_SIZE):
    """处理 **加粗** 与 `代码` 两种行内标记。"""
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith("**") and part.endswith("**"):
            run = paragraph.add_run(part[2:-2])
            set_run_font(run, size=size, bold=True)
        elif part.startswith("`") and part.endswith("`"):
            run = paragraph.add_run(part[1:-1])
            set_run_font(run, name=CODE_FONT, size=Pt(9.5), color=RGBColor(0xA6, 0x2A, 0x2A))
        else:
            run = paragraph.add_run(part)
            set_run_font(run, size=size)


def build(md_path: Path, out_path: Path) -> None:
    lines = md_path.read_text(encoding="utf-8").splitlines()
    doc = Document()

    section = doc.sections[0]
    section.page_width = Cm(21.0)
    section.page_height = Cm(29.7)
    section.top_margin = Cm(2.4)
    section.bottom_margin = Cm(2.4)
    section.left_margin = Cm(2.6)
    section.right_margin = Cm(2.6)

    normal = doc.styles["Normal"]
    normal.font.size = BODY_SIZE
    normal.font.name = CN_FONT
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), CN_FONT)
    normal.paragraph_format.line_spacing = 1.4
    normal.paragraph_format.space_after = Pt(4)

    for level, size in ((1, Pt(20)), (2, Pt(15)), (3, Pt(13)), (4, Pt(11.5))):
        style = doc.styles[f"Heading {level}"]
        style.font.size = size
        style.font.bold = True
        style.font.name = CN_FONT
        style.element.rPr.rFonts.set(qn("w:eastAsia"), CN_FONT)
        style.font.color.rgb = RGBColor(0x1F, 0x3B, 0x1F) if level <= 2 else RGBColor(0x33, 0x33, 0x33)
        style.paragraph_format.space_before = Pt(14 if level <= 2 else 10)
        style.paragraph_format.space_after = Pt(6)

    i = 0
    n = len(lines)
    first_heading_done = False

    while i < n:
        raw = lines[i]
        line = raw.rstrip()
        stripped = line.strip()

        # 代码块
        if stripped.startswith("```"):
            i += 1
            block = []
            while i < n and not lines[i].strip().startswith("```"):
                block.append(lines[i])
                i += 1
            i += 1
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.6)
            p.paragraph_format.line_spacing = 1.1
            run = p.add_run("\n".join(block))
            set_run_font(run, name=CODE_FONT, size=Pt(9))
            pPr = p._p.get_or_add_pPr()
            shd = pPr.makeelement(qn("w:shd"), {qn("w:val"): "clear", qn("w:fill"): "F4F4F4"})
            pPr.append(shd)
            continue

        # 图片
        m = re.match(r"^!\[[^\]]*\]\(([^)]+)\)$", stripped)
        if m:
            img = (md_path.parent / m.group(1)).resolve()
            if img.exists():
                p = doc.add_paragraph()
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                p.paragraph_format.space_before = Pt(8)
                p.paragraph_format.space_after = Pt(8)
                p.add_run().add_picture(str(img), width=IMAGE_WIDTH)
            else:
                p = doc.add_paragraph(f"[缺图] {m.group(1)}")
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            i += 1
            continue

        # 表格
        if stripped.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            if rows:
                cols = max(len(r) for r in rows)
                table = doc.add_table(rows=0, cols=cols)
                table.style = "Table Grid"
                table.alignment = WD_TABLE_ALIGNMENT.CENTER
                for r_idx, cells in enumerate(rows):
                    cells = cells + [""] * (cols - len(cells))
                    row = table.add_row()
                    for c_idx, text in enumerate(cells):
                        cell = row.cells[c_idx]
                        cell.text = ""
                        para = cell.paragraphs[0]
                        para.paragraph_format.space_after = Pt(2)
                        add_rich_text(para, text, size=Pt(9.5))
                        if r_idx == 0:
                            for run in para.runs:
                                run.bold = True
                doc.add_paragraph()
            continue

        if not stripped:
            i += 1
            continue

        # 分隔线
        if stripped in ("---", "***", "___"):
            p = doc.add_paragraph()
            pPr = p._p.get_or_add_pPr()
            borders = pPr.makeelement(qn("w:pBdr"), {})
            bottom = borders.makeelement(qn("w:bottom"), {
                qn("w:val"): "single", qn("w:sz"): "6",
                qn("w:space"): "1", qn("w:color"): "BFBFBF",
            })
            borders.append(bottom)
            pPr.append(borders)
            i += 1
            continue

        # 标题
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level = len(m.group(1))
            text = m.group(2).strip()
            heading = doc.add_heading(level=level)
            run = heading.add_run(text)
            set_run_font(run, size=Pt({1: 20, 2: 15, 3: 13, 4: 11.5}[level]), bold=True,
                         color=RGBColor(0x1F, 0x3B, 0x1F) if level <= 2 else RGBColor(0x33, 0x33, 0x33))
            if level == 1 and not first_heading_done:
                heading.alignment = WD_ALIGN_PARAGRAPH.CENTER
                first_heading_done = True
            i += 1
            continue

        # 引用
        if stripped.startswith(">"):
            text = re.sub(r"^>\s*", "", stripped)
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.6)
            add_rich_text(p, text, size=Pt(10))
            for run in p.runs:
                run.font.color.rgb = RGBColor(0x59, 0x59, 0x59)
                run.italic = True
            i += 1
            continue

        # 列表
        m = re.match(r"^[-*]\s+(.*)$", stripped)
        if m:
            p = doc.add_paragraph(style="List Bullet")
            p.paragraph_format.space_after = Pt(2)
            add_rich_text(p, m.group(1))
            i += 1
            continue

        m = re.match(r"^(\d+)\.\s+(.*)$", stripped)
        if m:
            p = doc.add_paragraph(style="List Number")
            p.paragraph_format.space_after = Pt(2)
            add_rich_text(p, m.group(2))
            i += 1
            continue

        # 正文
        p = doc.add_paragraph()
        p.paragraph_format.first_line_indent = Cm(0)
        add_rich_text(p, stripped)
        i += 1

    out_path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(out_path))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--md", default=str(ROOT / "docs" / "SYSTEM_DESIGN.md"))
    parser.add_argument("--out", default=str(ROOT / "docs" / "活动管家系统设计说明书.docx"))
    args = parser.parse_args()

    md_path = Path(args.md)
    out_path = Path(args.out)
    build(md_path, out_path)
    print(f"generated: {out_path}")
    print(f"size: {out_path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
