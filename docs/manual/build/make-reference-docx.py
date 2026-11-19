#!/usr/bin/env python3
"""
方案 A 辅助：生成 Pandoc 样式模板 reference.docx。

用法：
  pip install python-docx
  python3 make-reference-docx.py [output.docx]

生成后在 Word 中按需微调：
  - 正文：宋体/微软雅黑 小四（12pt），1.5 倍行距
  - 标题 1/2/3：黑体，18/15/13pt
  - 表格：Table Grid，表头加粗浅蓝底
  - 页边距：上下 2.54cm、左右 2.8cm
  - 页眉：手册名称；页脚：页码
"""
import sys
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt, Cm, RGBColor
from docx.enum.section import WD_SECTION


def set_cn_font(style, ascii_font, eastasia_font, size_pt, bold=False, color=None):
    style.font.name = ascii_font
    style.font.size = Pt(size_pt)
    style.font.bold = bold
    if color:
        style.font.color.rgb = RGBColor(*color)
    rpr = style.element.get_or_add_rPr()
    rfonts = rpr.find('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}rFonts')
    if rfonts is None:
        from docx.oxml import OxmlElement
        rfonts = OxmlElement('w:rFonts')
        rpr.append(rfonts)
    rfonts.set('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}eastAsia', eastasia_font)


def main(out_path='reference.docx'):
    doc = Document()

    # 页边距
    for section in doc.sections:
        section.top_margin = Cm(2.54)
        section.bottom_margin = Cm(2.54)
        section.left_margin = Cm(2.8)
        section.right_margin = Cm(2.8)

    # 正文
    normal = doc.styles['Normal']
    set_cn_font(normal, 'Calibri', '宋体', 12)
    normal.paragraph_format.line_spacing = 1.5
    normal.paragraph_format.space_after = Pt(6)

    # 标题
    specs = [
        ('Heading 1', 18, (0x1F, 0x38, 0x64)),
        ('Heading 2', 15, (0x2F, 0x54, 0x96)),
        ('Heading 3', 13, (0x2F, 0x54, 0x96)),
    ]
    for name, size, color in specs:
        style = doc.styles[name]
        set_cn_font(style, 'SimHei', '黑体', size, bold=True, color=color)
        style.paragraph_format.space_before = Pt(12)
        style.paragraph_format.space_after = Pt(6)

    # 页眉
    header = doc.sections[0].header
    header.is_linked_to_previous = False
    hp = header.paragraphs[0]
    hp.text = '金地美家供应链云平台操作手册 V1.0'
    hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for run in hp.runs:
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor(0x80, 0x80, 0x80)

    # 页脚页码
    footer = doc.sections[0].footer
    footer.is_linked_to_previous = False
    fp = footer.paragraphs[0]
    fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    fp.add_run('第 ')
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    run = fp.add_run()
    fld_begin = OxmlElement('w:fldChar'); fld_begin.set(qn('w:fldCharType'), 'begin')
    instr = OxmlElement('w:instrText'); instr.set(qn('xml:space'), 'preserve'); instr.text = ' PAGE '
    fld_end = OxmlElement('w:fldChar'); fld_end.set(qn('w:fldCharType'), 'end')
    run._r.append(fld_begin); run._r.append(instr); run._r.append(fld_end)
    fp.add_run(' 页')
    for r in fp.runs:
        r.font.size = Pt(9)
        r.font.color.rgb = RGBColor(0x80, 0x80, 0x80)

    doc.save(out_path)
    print(f'已生成 Pandoc 样式模板：{out_path}')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'reference.docx')
