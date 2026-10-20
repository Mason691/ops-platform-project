# -*- coding: utf-8 -*-
"""德保2025.xls → ops_business_data INSERT SQL（宽松导入：不校验、不匹配底价）"""
import xlrd, re

wb = xlrd.open_workbook("/tmp/debao2025.xls")
sh = wb.sheet_by_index(0)

CAT = {"燃气灶": "1", "热水器": "3"}  # 其余（商业报警器）存原文

def cell(r, c):
    c_ = sh.cell(r, c); v = c_.value
    if c_.ctype == 3:
        try: v = xlrd.xldate_as_datetime(v, wb.datemode).strftime("%Y-%m-%d")
        except Exception: pass
    elif c_.ctype == 2 and float(v).is_integer(): v = int(v)
    return "" if v is None else str(v).strip()

def norm_date(s):
    s = s.strip()
    if not s: return None
    m = re.match(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$", s)
    if m: return "%s-%02d-%02d" % (m.group(1), int(m.group(2)), int(m.group(3)))
    return s  # 已 ISO 或不可识别：宽松保留原样

def esc(v):
    if v is None or str(v) == "": return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"

cols = ("batch_no,company,customer,customer_phone,platform_member_id,brand,category,model,"
        "sale_price,agent,agent_phone,is_special,commission_rate,sale_date,receipt_date,"
        "finance_receipt_date,fund_dynamic_rows,receipt_no,department,retail_floor_price,"
        "group_lowest_price,calc_status,remark,corp_id,operator_id")

rows, cats, total = [], {}, 0
for r in range(2, sh.nrows):
    cat_cn = cell(r, 2)
    cat = CAT.get(cat_cn, cat_cn)
    cats[cat_cn] = cats.get(cat_cn, 0) + 1
    price = cell(r, 14) or "0"
    total += float(price)
    receipt = norm_date(cell(r, 11))
    finance = norm_date(cell(r, 12))
    vals = [
        esc("德保2025-001"), esc("德保"), esc(cell(r, 3)), esc(cell(r, 5)), "NULL",
        esc(cell(r, 7)), esc(cat), esc(cell(r, 8)),
        esc(price), "NULL", "NULL", esc(cell(r, 15)), esc(cell(r, 16) or None),
        esc(receipt), esc(receipt), esc(finance), "NULL", esc(cell(r, 13)),
        "NULL", "NULL", "NULL", esc("未计算"), esc(cell(r, 17) or None), "12", "12",
    ]
    rows.append("(" + ",".join(vals) + ")")

sql = "INSERT INTO ops_business_data (" + cols + ") VALUES\n" + ",\n".join(rows) + ";\n"
open("/tmp/debao2025.sql", "w", encoding="utf-8").write(sql)
print("rows:", len(rows))
print("categories:", cats)
print("sale_price sum:", total)
