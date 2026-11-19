#!/usr/bin/env python3
"""
方案 B：python-docx 直接生成 Word 手册。

用法：
  pip install python-docx
  python3 make-docx-python-docx.py [input.md] [output.docx]

默认输入 build/manual.md，输出 build/金地美家供应链云平台操作手册_V1.0_pythondocx.docx。
支持：封面（<!-- cover:start/end -->）、目录域、标题 1-4、表格、引用块、列表、
分页（<!-- pagebreak --> 与一级标题自动分页）、页眉、页脚页码。
"""
import os
import re
import sys
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.shared import Pt, Cm, RGBColor
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

HEADER_TEXT = '金地美家供应链云平台操作手册 V1.0'


def set_run_font(run, size=None, bold=None, color=None, eastasia='宋体', ascii_font='Calibri'):
    run.font.name = ascii_font
    run.font.size = Pt(size) if size else None
    run.font.bold = bold
    if color:
        run.font.color.rgb = RGBColor(*color)
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn('w:rFonts'))
    if rfonts is None:
        rfonts = OxmlElement('w:rFonts')
        rpr.append(rfonts)
    rfonts.set(qn('w:eastAsia'), eastasia)


def add_page_number(paragraph):
    run = paragraph.add_run()
    fld_begin = OxmlElement('w:fldChar'); fld_begin.set(qn('w:fldCharType'), 'begin')
    instr = OxmlElement('w:instrText'); instr.set(qn('xml:space'), 'preserve'); instr.text = ' PAGE '
    fld_end = OxmlElement('w:fldChar'); fld_end.set(qn('w:fldCharType'), 'end')
    run._r.append(fld_begin); run._r.append(instr); run._r.append(fld_end)


def add_toc(doc):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run('目录')
    set_run_font(run, 18, True, (0x1F, 0x38, 0x64), eastasia='黑体', ascii_font='SimHei')
    p2 = doc.add_paragraph()
    run = p2.add_run()
    fld_begin = OxmlElement('w:fldChar'); fld_begin.set(qn('w:fldCharType'), 'begin')
    instr = OxmlElement('w:instrText'); instr.set(qn('xml:space'), 'preserve')
    instr.text = ' TOC \\o "1-2" \\h \\z \\u '
    fld_end = OxmlElement('w:fldChar'); fld_end.set(qn('w:fldCharType'), 'end')
    run._r.append(fld_begin); run._r.append(instr); run._r.append(fld_end)
    doc.add_paragraph('（目录将在 Word 打开后自动更新；如未更新，请右键“更新域”或按 F9）')


def setup_document():
    doc = Document()
    for section in doc.sections:
        section.top_margin = Cm(2.54)
        section.bottom_margin = Cm(2.54)
        section.left_margin = Cm(2.8)
        section.right_margin = Cm(2.8)
        header = section.header
        hp = header.paragraphs[0]
        hp.text = HEADER_TEXT
        hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
        for r in hp.runs:
            r.font.size = Pt(9); r.font.color.rgb = RGBColor(0x80, 0x80, 0x80)
        footer = section.footer
        fp = footer.paragraphs[0]
        fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
        fp.add_run('第 ')
        add_page_number(fp)
        fp.add_run(' 页')
        for r in fp.runs:
            r.font.size = Pt(9); r.font.color.rgb = RGBColor(0x80, 0x80, 0x80)
    return doc


def add_cover(doc, lines):
    for idx, text in enumerate(lines):
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        if idx == 0:
            p.paragraph_format.space_before = Pt(180)
            p.paragraph_format.space_after = Pt(28)
            run = p.add_run(text)
            set_run_font(run, 28, True, eastasia='黑体', ascii_font='SimHei')
        else:
            p.paragraph_format.space_after = Pt(10)
            run = p.add_run(text)
            set_run_font(run, 14)
    doc.add_page_break()


def add_table(doc, rows):
    header = [c.strip() for c in rows[0].strip('|').split('|')]
    data = [[c.strip() for c in r.strip('|').split('|')] for r in rows[2:]]
    table = doc.add_table(rows=1, cols=len(header))
    table.style = 'Table Grid'
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = table.rows[0].cells
    for i, text in enumerate(header):
        hdr[i].text = ''
        run = hdr[i].paragraphs[0].add_run(text)
        set_run_font(run, 10, True)
        shading = OxmlElement('w:shd')
        shading.set(qn('w:fill'), 'D9E2F3')
        hdr[i]._tc.get_or_add_tcPr().append(shading)
    for row in data:
        cells = table.add_row().cells
        for i, text in enumerate(row[:len(header)]):
            cells[i].text = ''
            run = cells[i].paragraphs[0].add_run(text)
            set_run_font(run, 10)
    doc.add_paragraph()


def add_inline_runs(paragraph, text):
    """处理 **加粗** 与 `行内代码`（简化版）。"""
    for token in re.split(r'(\*\*[^*]+\*\*|`[^`]+`)', text):
        if not token:
            continue
        if token.startswith('**') and token.endswith('**'):
            run = paragraph.add_run(token[2:-2]); run.font.bold = True
        elif token.startswith('`') and token.endswith('`'):
            run = paragraph.add_run(token[1:-1])
            set_run_font(run, 10, False, eastasia='微软雅黑', ascii_font='Courier New')
        else:
            paragraph.add_run(token)


def convert(md_path, out_path):
    lines = open(md_path, encoding='utf-8').read().splitlines()
    doc = setup_document()
    i = 0
    cover_mode = False
    cover_lines = []
    h1_seen = False

    while i < len(lines):
        raw = lines[i]
        stripped = raw.strip()

        if stripped == '<!-- cover:start -->':
            cover_mode = True; cover_lines = []; i += 1; continue
        if stripped == '<!-- cover:end -->':
            cover_mode = False
            add_cover(doc, cover_lines)
            h1_seen = True  # 封面已分页，后续第一个一级标题不再重复分页
            i += 1; continue
        if stripped == '<!-- toc -->':
            add_toc(doc); i += 1; continue
        if stripped == '<!-- pagebreak -->':
            doc.add_page_break(); i += 1; continue
        if cover_mode:
            if stripped:
                cover_lines.append(stripped)
            i += 1; continue

        # 代码块（简单等宽段落）
        if stripped.startswith('```'):
            code = []; i += 1
            while i < len(lines) and not lines[i].strip().startswith('```'):
                code.append(lines[i]); i += 1
            p = doc.add_paragraph()
            run = p.add_run('\n'.join(code))
            set_run_font(run, 9, False, eastasia='微软雅黑', ascii_font='Courier New')
            i += 1; continue

        # 表格
        if stripped.startswith('|'):
            table_rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                table_rows.append(lines[i]); i += 1
            if len(table_rows) >= 3:
                add_table(doc, table_rows)
            continue

        # 标题
        m = re.match(r'^(#{1,4})\s+(.*)', raw)
        if m:
            level = len(m.group(1))
            if level == 1:
                if h1_seen:
                    doc.add_page_break()
                h1_seen = True
            heading = doc.add_heading(level=min(level, 4))
            add_inline_runs(heading, m.group(2).strip())
            for r in heading.runs:
                set_run_font(r, {1: 18, 2: 15, 3: 13, 4: 12}[level], True,
                             (0x1F, 0x38, 0x64) if level == 1 else (0x2F, 0x54, 0x96),
                             eastasia='黑体', ascii_font='SimHei')
            i += 1; continue

        # 引用块
        if stripped.startswith('>'):
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.5)
            add_inline_runs(p, re.sub(r'^>\s?', '', stripped))
            for r in p.runs:
                r.font.color.rgb = RGBColor(0x59, 0x59, 0x59)
            i += 1; continue

        # 列表
        m = re.match(r'^\s*[-*]\s+(.*)', raw)
        if m:
            p = doc.add_paragraph(style='List Bullet')
            add_inline_runs(p, m.group(1))
            i += 1; continue
        m = re.match(r'^\s*\d+\.\s+(.*)', raw)
        if m:
            p = doc.add_paragraph(style='List Number')
            add_inline_runs(p, m.group(1))
            i += 1; continue

        if not stripped:
            i += 1; continue
        if re.match(r'^-{3,}$', stripped):
            i += 1; continue

        p = doc.add_paragraph()
        add_inline_runs(p, stripped)
        i += 1

    doc.save(out_path)
    print(f'已生成：{out_path}')


if __name__ == '__main__':
    base = os.path.dirname(os.path.abspath(__file__))
    md = sys.argv[1] if len(sys.argv) > 1 else os.path.join(base, 'manual.md')
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        base, '金地美家供应链云平台操作手册_V1.0_pythondocx.docx')
    convert(md, out)
