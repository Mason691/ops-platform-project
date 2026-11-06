#!/usr/bin/env python3
"""给业务数据模板追加「收货地址」列（仅 patch 业务数据 sheet，不重存工作簿）。

背景：模板是 12 sheet 的台账工作簿，内含 11 个 drawing 与大量图片。
openpyxl 另存会丢失其它 sheet 的图片，因此只在 XML 层给「业务数据」
sheet（xl/worksheets/sheet10.xml）追加 T 列表头，其余 zip 条目原样保留。

用法：
    python3 sql/patch_business_data_template_delivery_address.py <src.xlsx> <dst.xlsx>

幂等：若 T1 已存在则直接复制，不重复追加。
"""

import re
import sys
import zipfile

SHEET = "xl/worksheets/sheet10.xml"
HEADER = "收货地址"


def patch_sheet(xml: str) -> tuple[str, bool]:
    if '<c r="T1"' in xml:
        return xml, False

    dimension = re.search(r'<dimension ref="A1:S(\d+)"/>', xml)
    if not dimension:
        raise SystemExit("未找到业务数据 sheet 的 A1:S 维度，模板结构可能已变化")
    xml = (xml[:dimension.start()]
           + '<dimension ref="A1:T%s"/>' % dimension.group(1)
           + xml[dimension.end():])

    row1 = re.search(r'<row r="1"[^>]*>.*?</row>', xml, re.S)
    if not row1:
        raise SystemExit("未找到业务数据 sheet 的表头行")
    row_xml = row1.group(0)

    style_match = re.search(r'<c r="S1"[^>]*\bs="(\d+)"', row_xml)
    style = style_match.group(1) if style_match else "43"
    if 'spans="1:19"' in row_xml:
        row_xml = row_xml.replace('spans="1:19"', 'spans="1:20"', 1)
    elif 'spans="' not in row_xml:
        row_xml = row_xml.replace('<row r="1"', '<row r="1" spans="1:20"', 1)

    cell = ('<c r="T1" s="%s" t="inlineStr"><is><t>%s</t></is></c>'
            % (style, HEADER))
    row_xml = row_xml.replace("</row>", cell + "</row>", 1)
    xml = xml[:row1.start()] + row_xml + xml[row1.end():]

    if 'min="20"' not in xml:
        xml = xml.replace(
            "</cols>",
            '<col min="20" max="20" width="18" customWidth="1"/></cols>',
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

    if '<c r="T1"' not in sheet or HEADER not in sheet:
        raise SystemExit("收货地址表头未写入")
    if 'ref="A1:T' not in sheet or 'min="20"' not in sheet:
        raise SystemExit("维度/列宽未更新")
    print("patched=%s entries=%d drawings=%d" % (
        patched,
        len(entries),
        sum(1 for info, _ in entries if info.filename.startswith("xl/drawings/drawing")),
    ))


if __name__ == "__main__":
    main()
