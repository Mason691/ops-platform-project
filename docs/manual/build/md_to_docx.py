#!/usr/bin/env python3
"""
Markdown → Word (.docx) 转换器 — 纯标准库实现（零第三方依赖）。

用途：将 docs/manual 下的 Markdown 手册转成可交付的 Word 版本。
用法：python3 md_to_docx.py <input.md> <output.docx>
      （无参数时默认 build/manual.md → build/操作手册.docx）

为何不用 python-docx：本机 Python 无 pip 且外网不可达，python-docx 装不上；
标准库 zipfile + OOXML 直接生成，Word/WPS 均可正常打开。

除通用 Markdown 语法外，本脚本还支持手册专用标记：
  <!-- cover:start --> ... <!-- cover:end -->  封面区（居中大标题/信息行）
  <!-- toc -->                                 目录域（Word 打开后按 F9 更新）
  <!-- pagebreak -->                           手动分页
一级标题（# 第X章）自动分页，页眉为手册名称，页脚为页码。
"""

import argparse
import re
import zipfile
from xml.sax.saxutils import escape

# ── 命名空间与公共 XML 片段 ─────────────────────────────────────────────────────
W = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
     'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"')

CONTENT_TYPES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>'''

RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

EMPTY_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>'''

DOC_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdHeader1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
  <Relationship Id="rIdFooter1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
</Relationships>'''

HEADER_XML = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr {W}>
  <w:p><w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r><w:rPr><w:sz w:val="18"/><w:szCs w:val="18"/><w:color w:val="808080"/></w:rPr>
      <w:t>金地美家供应链云平台操作手册 V1.0</w:t></w:r>
  </w:p>
</w:hdr>'''

FOOTER_XML = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr {W}>
  <w:p><w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r><w:rPr><w:sz w:val="18"/><w:szCs w:val="18"/><w:color w:val="808080"/></w:rPr>
      <w:t xml:space="preserve">第 </w:t></w:r>
    <w:r><w:fldChar w:fldCharType="begin"/></w:r>
    <w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
    <w:r><w:fldChar w:fldCharType="separate"/></w:r>
    <w:r><w:t>1</w:t></w:r>
    <w:r><w:fldChar w:fldCharType="end"/></w:r>
    <w:r><w:rPr><w:sz w:val="18"/><w:szCs w:val="18"/><w:color w:val="808080"/></w:rPr>
      <w:t xml:space="preserve"> 页</w:t></w:r>
  </w:p>
</w:ftr>'''

# 正文字体：西文 Calibri，中文微软雅黑；代码块 Courier New（中文仍用微软雅黑）
STYLES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
  <w:docDefaults>
    <w:rPrDefault><w:rPr>
      <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="微软雅黑" w:cs="Calibri"/>
      <w:sz w:val="22"/><w:szCs w:val="22"/>
    </w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/><w:qFormat/>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:outlineLvl w:val="0"/><w:spacing w:before="280" w:after="140"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="SimHei" w:hAnsi="SimHei" w:eastAsia="黑体"/><w:b/><w:color w:val="1F3864"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:outlineLvl w:val="1"/><w:spacing w:before="200" w:after="100"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="SimHei" w:hAnsi="SimHei" w:eastAsia="黑体"/><w:b/><w:color w:val="2F5496"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading3">
    <w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:outlineLvl w:val="2"/><w:spacing w:before="160" w:after="80"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="SimHei" w:hAnsi="SimHei" w:eastAsia="黑体"/><w:b/><w:color w:val="2F5496"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading4">
    <w:name w:val="heading 4"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:outlineLvl w:val="3"/><w:spacing w:before="120" w:after="60"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="SimHei" w:hAnsi="SimHei" w:eastAsia="黑体"/><w:b/><w:color w:val="404040"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="CodeBlock">
    <w:name w:val="Code Block"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/>
      <w:spacing w:before="80" w:after="80"/><w:ind w:left="120" w:right="120"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:eastAsia="微软雅黑"/>
      <w:color w:val="333333"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Quote">
    <w:name w:val="Quote"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:pBdr><w:left w:val="single" w:sz="16" w:space="6" w:color="2F5496"/></w:pBdr>
      <w:ind w:left="420"/><w:spacing w:before="60" w:after="60"/></w:pPr>
    <w:rPr><w:color w:val="595959"/></w:rPr>
  </w:style>
  <w:style w:type="table" w:styleId="TableGrid">
    <w:name w:val="Table Grid"/>
    <w:tblPr>
      <w:tblBorders>
        <w:top w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
        <w:left w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
        <w:bottom w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
        <w:right w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
        <w:insideH w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
        <w:insideV w:val="single" w:sz="4" w:space="0" w:color="9CB3D4"/>
      </w:tblBorders>
      <w:tblCellMar>
        <w:top w:w="57" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>
        <w:bottom w:w="57" w:type="dxa"/><w:right w:w="108" w:type="dxa"/>
      </w:tblCellMar>
    </w:tblPr>
  </w:style>
</w:styles>'''

# ── 行内解析：**加粗** 与 `行内代码`（加粗可嵌套行内代码） ────────────────────────
INLINE_RE = re.compile(r'(\*\*[^*]+\*\*|`[^`]+`)')
CODE_RE = re.compile(r'(`[^`]+`)')

def parse_inline(text):
    """返回 (kind, text) 列表：kind ∈ {t 普通, b 加粗, c 代码, bc 加粗+代码}"""
    runs = []
    for tok in INLINE_RE.split(text):
        if not tok:
            continue
        if tok.startswith('**') and tok.endswith('**') and len(tok) >= 4:
            # 加粗内部可能嵌套行内代码（如 **禁用 `@PreAuthorize`**），递归拆分
            for sub in CODE_RE.split(tok[2:-2]):
                if not sub:
                    continue
                if sub.startswith('`') and sub.endswith('`') and len(sub) >= 2:
                    runs.append(('bc', sub[1:-1]))
                else:
                    runs.append(('b', sub))
        elif tok.startswith('`') and tok.endswith('`') and len(tok) >= 2:
            runs.append(('c', tok[1:-1]))
        else:
            runs.append(('t', tok))
    return runs

def runs_xml(runs, force_header=False):
    """runs → <w:r> 序列；force_header 时全部白字加粗（表头用）"""
    out = []
    for kind, text in runs:
        t = escape(text)
        if force_header:
            rpr = '<w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr>'
        elif kind == 'b':
            rpr = '<w:rPr><w:b/></w:rPr>'
        elif kind == 'bc':
            rpr = ('<w:rPr><w:b/><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:eastAsia="微软雅黑"/>'
                   '<w:color w:val="C7254E"/><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr>')
        elif kind == 'c':
            rpr = ('<w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:eastAsia="微软雅黑"/>'
                   '<w:color w:val="C7254E"/><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr>')
        else:
            rpr = ''
        out.append(f'<w:r>{rpr}<w:t xml:space="preserve">{t}</w:t></w:r>')
    return ''.join(out)

# ── 段落构建 ────────────────────────────────────────────────────────────────────
def para_xml(runs, style=None, indent=None, hanging=None, shading=None,
             pborder=None, br_lines=None):
    ppr = '<w:pPr>'
    if style:
        ppr += f'<w:pStyle w:val="{style}"/>'
    if shading:
        ppr += f'<w:shd w:val="clear" w:color="auto" w:fill="{shading}"/>'
    if pborder:
        ppr += f'<w:pBdr>{pborder}</w:pBdr>'
    if indent is not None or hanging is not None:
        ppr += '<w:ind w:left="%d" w:hanging="%d"/>' % (
            indent if indent is not None else 0, hanging if hanging is not None else 0)
    ppr += '</w:pPr>'
    return f'<w:p>{ppr}{runs}</w:p>'

def bullet_para(text, level=0):
    """手动项目符号：缩进随层级加深，不依赖 numbering.xml"""
    indent = 360 * (level + 1)
    return para_xml('<w:r><w:t xml:space="preserve">• </w:t></w:r>' + runs_xml(parse_inline(text)),
                    indent=indent, hanging=200)

def numbered_para(text):
    return para_xml('<w:r><w:t xml:space="preserve">1. </w:t></w:r>' + runs_xml(parse_inline(text)),
                    indent=360, hanging=200)

def code_para(lines):
    """整个围栏代码块 = 一个 CodeBlock 段落，行间用 <w:br/>"""
    body = escape('\n'.join(lines)).replace('\n', '<w:br/>')
    return (f'<w:p><w:pPr><w:pStyle w:val="CodeBlock"/></w:pPr>'
            f'<w:r><w:t xml:space="preserve">{body}</w:t></w:r></w:p>')

def quote_para(text):
    return para_xml(runs_xml(parse_inline(text)), style='Quote')

def hr_para():
    return para_xml('', pborder='<w:bottom w:val="single" w:sz="6" w:space="1" w:color="BFBFBF"/>')

def heading_para(level, text):
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)
    return para_xml(runs_xml(parse_inline(text)), style=f'Heading{level}')

def normal_para(text):
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)
    return para_xml(runs_xml(parse_inline(text)))

# ── 表格构建 ────────────────────────────────────────────────────────────────────
def cell_xml(text, is_header, zebra):
    shade = ('2F5496' if is_header else ('EEF3FB' if zebra else None))
    tcpr = ''
    if shade:
        tcpr += f'<w:shd w:val="clear" w:color="auto" w:fill="{shade}"/>'
    runs = runs_xml(parse_inline(text), force_header=is_header)
    return (f'<w:tc><w:tcPr>{tcpr}</w:tcPr>'
            f'<w:p><w:pPr><w:spacing w:before="20" w:after="20"/></w:pPr>{runs}</w:p></w:tc>')

def table_xml(rows):
    """rows[0]=表头, rows[1]=分隔行(---), rows[2:]=数据"""
    if len(rows) < 3:
        return ''
    header = [c.strip() for c in rows[0].strip('|').split('|')]
    data = [[c.strip() for c in r.strip('|').split('|')] for r in rows[2:]]
    out = ['<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>'
           '<w:tblW w:w="0" w:type="auto"/><w:tblLayout w:type="autofit"/></w:tblPr>']
    out.append('<w:tr><w:trPr><w:tblHeader/></w:trPr>' +
               ''.join(cell_xml(h, True, False) for h in header) + '</w:tr>')
    for ri, row in enumerate(data):
        out.append('<w:tr>' + ''.join(
            cell_xml(row[ci], False, ri % 2 == 0) for ci in range(len(header))) + '</w:tr>')
    out.append('</w:tbl>')
    return ''.join(out)

# ── 打包 ────────────────────────────────────────────────────────────────────────
def build_document(body_xml):
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            f'<w:document {W}><w:body>{body_xml}'
            '<w:sectPr>'
            '<w:headerReference w:type="default" r:id="rIdHeader1"/>'
            '<w:footerReference w:type="default" r:id="rIdFooter1"/>'
            '<w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1701" '
            'w:header="851" w:footer="992" w:gutter="0"/></w:sectPr>'
            '</w:body></w:document>')

def page_break_para():
    return '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'

def toc_para():
    """插入 Word 目录域；打开文档后按 F9/右键更新字段生成目录。"""
    return ('<w:p><w:pPr><w:pStyle w:val="Heading1"/><w:jc w:val="center"/></w:pPr>'
            '<w:r><w:t>目录</w:t></w:r></w:p>'
            '<w:p><w:pPr><w:spacing w:after="120"/></w:pPr>'
            '<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r>'
            '<w:r><w:instrText xml:space="preserve"> TOC \\o "1-2" \\h \\z \\u </w:instrText></w:r>'
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
            '<w:r><w:t>（目录将在 Word 打开后自动更新；如未更新，请右键“更新域”或按 F9）</w:t></w:r>'
            '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
            '</w:p>')

def cover_para(text, index):
    """封面行：首行大标题，其余信息行居中。"""
    if index == 0:
        return ('<w:p><w:pPr><w:jc w:val="center"/><w:spacing w:before="2400" w:after="400"/></w:pPr>'
                f'<w:r><w:rPr><w:rFonts w:ascii="SimHei" w:hAnsi="SimHei" w:eastAsia="黑体"/>'
                f'<w:b/><w:sz w:val="56"/><w:szCs w:val="56"/></w:rPr>'
                f'<w:t>{escape(text)}</w:t></w:r></w:p>')
    return ('<w:p><w:pPr><w:jc w:val="center"/><w:spacing w:after="160"/></w:pPr>'
            f'<w:r><w:rPr><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr>'
            f'<w:t>{escape(text)}</w:t></w:r></w:p>')

def write_docx(path, body_xml):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', CONTENT_TYPES)
        z.writestr('_rels/.rels', RELS)
        z.writestr('word/_rels/document.xml.rels', DOC_RELS)
        z.writestr('word/styles.xml', STYLES)
        z.writestr('word/header1.xml', HEADER_XML)
        z.writestr('word/footer1.xml', FOOTER_XML)
        z.writestr('word/document.xml', build_document(body_xml))

# ── Markdown 主解析循环 ─────────────────────────────────────────────────────────
def convert(md_file, docx_file):
    with open(md_file, encoding='utf-8') as f:
        lines = [l.rstrip('\n') for l in f.readlines()]

    body = []
    i = 0
    cover_mode = False
    cover_index = 0
    h1_seen = False
    while i < len(lines):
        line = lines[i]

        # 手册专用标记
        stripped = line.strip()
        if stripped == '<!-- cover:start -->':
            cover_mode = True
            cover_index = 0
            i += 1
            continue
        if stripped == '<!-- cover:end -->':
            cover_mode = False
            # 封面单独成页；后续第一个一级标题不再重复分页
            body.append(page_break_para())
            h1_seen = True
            i += 1
            continue
        if stripped == '<!-- toc -->':
            body.append(toc_para())
            i += 1
            continue
        if stripped == '<!-- pagebreak -->':
            body.append(page_break_para())
            i += 1
            continue
        if cover_mode:
            if stripped:
                body.append(cover_para(stripped, cover_index))
                cover_index += 1
            i += 1
            continue

        # 围栏代码块
        if line.startswith('```'):
            code_lines, i = [], i + 1
            while i < len(lines) and not lines[i].startswith('```'):
                code_lines.append(lines[i]); i += 1
            body.append(code_para(code_lines))
            i += 1
            continue

        # 分隔线
        if re.match(r'^-{3,}$', line.strip()):
            body.append(hr_para()); i += 1
            continue

        # 表格
        if line.startswith('|'):
            tbl = []
            while i < len(lines) and lines[i].startswith('|'):
                tbl.append(lines[i]); i += 1
            body.append(table_xml(tbl))
            body.append('<w:p/>')
            continue

        # 标题（一级标题自动分页；文档第一个一级标题不分页）
        m = re.match(r'^(#{1,4})\s+(.*)', line)
        if m:
            level = len(m.group(1))
            if level == 1:
                if h1_seen:
                    body.append(page_break_para())
                h1_seen = True
            body.append(heading_para(level, m.group(2))); i += 1
            continue

        # 引用块（连续 > 行各自成段，共享左侧蓝色竖线样式）
        if line.startswith('>'):
            while i < len(lines) and lines[i].startswith('>'):
                body.append(quote_para(re.sub(r'^>\s?', '', lines[i]))); i += 1
            continue

        # 无序列表（支持一层缩进）
        m = re.match(r'^(\s*)[-*]\s+(.*)', line)
        if m:
            body.append(bullet_para(m.group(2), level=len(m.group(1)) // 2)); i += 1
            continue

        # 有序列表（手动 "1. " 前缀）
        m = re.match(r'^(\s*)\d+\.\s+(.*)', line)
        if m:
            body.append(numbered_para(re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', m.group(2)))); i += 1
            continue

        # 空行
        if not line.strip():
            i += 1
            continue

        # 普通段落
        body.append(normal_para(line)); i += 1

    write_docx(docx_file, ''.join(body))
    print(f'Saved: {docx_file}')

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description='Markdown → Word(.docx) 纯标准库转换器')
    ap.add_argument('input', nargs='?', default='technical-architecture.md', help='输入 Markdown（默认 technical-architecture.md）')
    ap.add_argument('output', nargs='?', default='technical-architecture.docx', help='输出 docx（默认 technical-architecture.docx）')
    args = ap.parse_args()
    convert(args.input, args.output)
