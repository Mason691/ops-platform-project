#!/usr/bin/env python3
"""把业务数据模板的地址列拆成「小区」+「详细地址」（仅 patch 业务数据 sheet）。

背景：模板是 12 sheet 的台账工作簿，内含 11 个 drawing 与大量图片。
openpyxl 另存会丢失其它 sheet 的图片，因此只在 XML 层改「业务数据」
sheet（xl/worksheets/sheet10.xml）：T 列表头「收货地址」改为「小区」，
并在 T 后追加 U 列「详细地址」，其余 zip 条目原样保留。

用法：
    python3 sql/patch_business_data_template_community_detail_address.py <src.xlsx> <dst.xlsx>

幂等：T1=小区、U1=详细地址 时直接复制，不重复追加。
"""

import re
import sys
import zipfile

SHEET = "xl/worksheets/sheet10.xml"
COMMUNITY = "小区"
DETAIL = "详细地址"


def cell_text(cell: str) -> str:
    match = re.search(r"<is><t>([^<]*)</t></is>", cell)
    if match:
        return match.group(1)
    match = re.search(r"<v>([^<]*)</v>", cell)
    return match.group(1) if match else ""


def patch_sheet(xml: str) -> tuple[str, bool]:
    t_match = re.search(r'<c r="T1"[^>]*>.*?</c>', xml, re.S)
    u_match = re.search(r'<c r="U1"[^>]*>.*?</c>', xml, re.S)
    if not t_match:
        raise SystemExit("未找到业务数据 sheet 的 T1 表头，模板结构可能已变化")
    if u_match and cell_text(t_match.group(0)) == COMMUNITY \
            and cell_text(u_match.group(0)) == DETAIL:
        return xml, False

    style_match = re.search(r'\bs="(\d+)"', t_match.group(0))
    style = style_match.group(1) if style_match else "43"

    dimension = re.search(r'<dimension ref="A1:(?:T|U)(\d+)"/>', xml)
    if not dimension:
        raise SystemExit("未找到业务数据 sheet 的 A1:T/U 维度，模板结构可能已变化")
    xml = (xml[:dimension.start()]
           + '<dimension ref="A1:U%s"/>' % dimension.group(1)
           + xml[dimension.end():])

    row1 = re.search(r'<row r="1"[^>]*>.*?</row>', xml, re.S)
    if not row1:
        raise SystemExit("未找到业务数据 sheet 的表头行")
    row_xml = row1.group(0)
    if 'spans="1:20"' in row_xml:
        row_xml = row_xml.replace('spans="1:20"', 'spans="1:21"', 1)
    elif 'spans="' not in row_xml:
        row_xml = row_xml.replace('<row r="1"', '<row r="1" spans="1:21"', 1)

    # 原位替换 T1 为「小区」，紧随其后追加 U1「详细地址」（含 U1 时一并替换旧内容）
    t_cell = '<c r="T1" s="%s" t="inlineStr"><is><t>%s</t></is></c>' % (style, COMMUNITY)
    u_cell = '<c r="U1" s="%s" t="inlineStr"><is><t>%s</t></is></c>' % (style, DETAIL)
    # 偏移必须相对 row_xml 计算（u_match 是对整份 sheet 匹配的，不能直接用于 row_xml）
    u_in_row = re.search(r'<c r="U1"[^>]*>.*?</c>', row_xml, re.S)
    if u_in_row:
        row_xml = row_xml[:u_in_row.start()] + u_cell + row_xml[u_in_row.end():]
    row_xml = re.sub(r'<c r="T1"[^>]*>.*?</c>', t_cell, row_xml, count=1, flags=re.S)
    if '<c r="U1"' not in row_xml:
        row_xml = row_xml.replace(t_cell, t_cell + u_cell, 1)
    xml = xml[:row1.start()] + row_xml + xml[row1.end():]

    if 'min="21"' not in xml:
        xml = xml.replace(
            "</cols>",
            '<col min="21" max="21" width="28" customWidth="1"/></cols>',
            1,
        )
    return xml, True


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]

    with zipfile.ZipFile(src) as zin:
        entries = [(info, zin.read(info.filename)) for info in zin.infolist()]

    if not any(info.filename == SHEET for info, _ in entries):
        raise SystemExit("模板缺少 %s" % SHEET)

    patched = False
    output = []
    for info, data in entries:
        if info.filename == SHEET:
            xml, patched = patch_sheet(data.decode("utf-8"))
            data = xml.encode("utf-8")
        output.append((info, data))

    with zipfile.ZipFile(dst, "w") as zout:
        for info, data in output:
            zout.writestr(info, data)

    with zipfile.ZipFile(dst) as znew:
        if set(znew.namelist()) != {info.filename for info, _ in entries}:
            raise SystemExit("zip 条目发生变化，模板可能损坏")
        for info, data in entries:
            if info.filename != SHEET and znew.read(info.filename) != data:
                raise SystemExit("非业务数据 sheet 条目被修改：%s" % info.filename)
        sheet = znew.read(SHEET).decode("utf-8")

    t_cell = re.search(r'<c r="T1"[^>]*>.*?</c>', sheet, re.S)
    u_cell = re.search(r'<c r="U1"[^>]*>.*?</c>', sheet, re.S)
    if not t_cell or cell_text(t_cell.group(0)) != COMMUNITY:
        raise SystemExit("小区表头未写入")
    if not u_cell or cell_text(u_cell.group(0)) != DETAIL:
        raise SystemExit("详细地址表头未写入")
    if 'ref="A1:U' not in sheet or 'min="21"' not in sheet:
        raise SystemExit("维度/列宽未更新")
    print("patched=%s entries=%d drawings=%d" % (
        patched,
        len(entries),
        sum(1 for info, _ in entries if info.filename.startswith("xl/drawings/drawing")),
    ))


if __name__ == "__main__":
    main()
