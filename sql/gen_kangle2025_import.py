# -*- coding: utf-8 -*-
"""康乐2025.xls → ops_business_data（宽松导入 + 品牌规格型号拆分 + 会员匹配/创建）"""
import xlrd, re, subprocess, json, collections

BATCH = "康乐2025-001"
CORP = 13
BRANDS = ["小松鼠", "万和", "万家乐", "美的"]
CATEGORY_WORDS = ["燃气灶", "热水器", "壁挂炉", "灶具", "抽油烟机", "报警器"]
CAT = {"壁挂炉": "4", "灶具": "1", "热水器": "3"}   # 其余（商业报警器/居民报警器）存原文
QTY_UNITS = "台个套只部支项组件"
QTY_RE = re.compile(r"[ ]+(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)?\s*$")
NOTE_RE = re.compile(r"原有|原小|更换")

def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", "mysql", "mysql", "-uroot", "-padmin.2026",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, text=True)
    if out.returncode != 0:
        print("MYSQL ERR:", out.stderr[:300]); raise SystemExit(1)
    return out.stdout

def cell(sh, r, c, datemode):
    c_ = sh.cell(r, c); v = c_.value
    if c_.ctype == 3:
        try: v = xlrd.xldate_as_datetime(v, datemode).strftime("%Y-%m-%d")
        except Exception: pass
    elif c_.ctype == 2:
        f = float(v)
        v = int(f) if f.is_integer() else f
    return "" if v is None else str(v).strip()

def norm_date(s):
    s = s.strip()
    if not s: return None
    m = re.match(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$", s)
    if m: return "%s-%02d-%02d" % (m.group(1), int(m.group(2)), int(m.group(3)))
    return s

def esc(v):
    if v is None or str(v) == "": return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"

def split_brand_model(cell_text, total_qty, price_cell, warnings, row_num):
    """拆分品牌规格型号 → [(brand, model, qty)]；返回组合列表与退化标记"""
    combos = []
    segs = [s.strip() for s in re.split(r"[\n\r]+", cell_text) if s.strip()]
    if not segs:
        return [("", "", None)], False
    for seg in segs:
        if NOTE_RE.search(seg):
            warnings.append(f"第{row_num}行：含更换/原有标注的旧型号段跳过 [{seg}]")
            continue
        qty = None
        m = QTY_RE.search(seg)
        if m:
            qty = float(m.group(1)); qty = int(qty) if qty == int(qty) else qty
            seg = seg[:m.start()].strip()
        brand, model = "", seg
        for b in BRANDS:
            if seg.startswith(b):
                brand = b
                rest = seg[len(b):].strip()
                for cw in CATEGORY_WORDS:
                    if rest.startswith(cw):
                        rest = rest[len(cw):].strip()
                rest = rest.lstrip("：: ")
                model = rest
                break
        else:
            cm = re.match(r"^([^\d：:]+)[：:]\s*(.+)$", seg)
            if cm and seg not in ("可燃气体报警器", "商业报警器", "居民报警器"):
                brand, model = cm.group(1).strip(), cm.group(2).strip()
        combos.append((brand, model if model else "", qty))
    # 单价列多值配对（1台14000⏎1台8000 或 12500⏎10000）
    price_segs = [s.strip() for s in re.split(r"[\n\r]+", price_cell) if s.strip()]
    pq = []
    for ps in price_segs:
        pm = re.match(r"^(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)?", ps)
        if pm: pq.append((float(pm.group(1)) if pm.group(1) else None, pm.group(2) or None))
    resolved = []
    for i, (b, mo, q) in enumerate(combos):
        if q is None:
            if len(price_segs) > 1 and len(pq) == len(combos) and pq[i][0] is not None:
                q = pq[i][0]; q = int(q) if q == int(q) else q
            elif len(combos) == 1:
                try: q = int(float(total_qty)) if total_qty else None
                except (ValueError, TypeError): q = None
            else:
                warnings.append(f"第{row_num}行：多型号但无法可靠识别数量，第{i+1}个组合数量置空 [{cell_text}]")
        resolved.append((b, mo, q))
    return resolved, False

def main():
    wb = xlrd.open_workbook("/tmp/kangle2025.xls")
    sh = wb.sheet_by_index(0)
    dm = wb.datemode
    # 会员全量预取
    phone2id = {}
    for line in mysql("SELECT phone, id FROM ai_platform.sys_member WHERE delete_flag=0 AND phone IS NOT NULL").splitlines():
        parts = line.split("\t")
        if len(parts) == 2: phone2id[parts[0]] = int(parts[1])
    name2id = collections.defaultdict(list)
    for line in mysql("SELECT id, real_name, nickname FROM ai_platform.sys_member WHERE delete_flag=0 AND corp_id=%d" % CORP).splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            for nm in (parts[1], parts[2]):
                if nm: name2id[nm].append(int(parts[0]))
    name2id = {k: min(v) for k, v in name2id.items()}

    rows, skipped, warnings, created = [], [], [], {}
    for r in range(2, sh.nrows):
        vals = {c: cell(sh, r, c, dm) for c in range(51)}
        if not any(vals[c] for c in (2, 4, 8, 9, 13, 14, 15)):
            continue
        phone = re.sub(r"\s+", "", vals[4]).rstrip(".")
        if not re.match(r"^1[3-9]\d{9}$", phone):
            skipped.append((r, vals[4] or "(空)"))
            continue
        name = "".join([ln.strip() for ln in vals[2].splitlines()])
        address = vals[3]
        category = CAT.get(vals[8], vals[8])
        combos, _ = split_brand_model(vals[9], vals[10], vals[11], warnings, r)
        receipt = norm_date(vals[13])
        member_id = phone2id.get(phone)
        if member_id is None and name in name2id:
            member_id = name2id[name]
        if member_id is None:
            if phone not in created:
                created[phone] = (name, address)
        rows.append(dict(r=r, phone=phone, name=name, category=category,
                         combos=combos, receipt=receipt, receipt_no=vals[14],
                         price=vals[15], remark=vals[50], member=member_id))
    # SQL 生成
    sql = ["USE mall_db;"]
    member_rows = []
    for phone, (name, addr) in created.items():
        member_rows.append("(" + ",".join([esc(phone), esc(name), esc(name),
            "1", esc("业务数据导入自动创建"), esc(addr or None), str(CORP)]) + ")")
    if member_rows:
        sql.append("INSERT INTO ai_platform.sys_member (phone, nickname, real_name, status, remark, address, corp_id) VALUES\n"
                   + ",\n".join(member_rows) + ";")
        sql.append("-- 新会员 id 回填：按手机号关联")
        sql.append("UPDATE ops_business_data bd JOIN ai_platform.sys_member m "
                   "ON m.phone COLLATE utf8mb4_unicode_ci = bd.customer_phone AND m.delete_flag=0 "
                   "SET bd.platform_member_id = m.id WHERE bd.batch_no = '%s' AND bd.platform_member_id IS NULL;" % BATCH)
    cols = ("batch_no,company,customer,customer_phone,platform_member_id,brand,category,model,"
            "sale_quantity,sale_price,agent,agent_phone,is_special,commission_rate,sale_date,"
            "receipt_date,finance_receipt_date,fund_dynamic_rows,receipt_no,department,"
            "retail_floor_price,group_lowest_price,calc_status,remark,corp_id,operator_id")
    recs, origin_map = [], []
    for row in rows:
        mid = str(row["member"]) if row["member"] is not None else (
            "(SELECT id FROM ai_platform.sys_member WHERE phone = '%s' AND delete_flag = 0 LIMIT 1)" % row["phone"])
        for b, mo, q in row["combos"]:
            recs.append("(" + ",".join([
                esc(BATCH), esc("康乐"), esc(row["name"]), esc(row["phone"]),
                mid, esc(b or None), esc(row["category"]), esc(mo or None),
                str(q) if q is not None else "NULL", esc(row["price"]),
                "NULL", "NULL", esc("否"), "0", esc(row["receipt"]), esc(row["receipt"]),
                esc(row["receipt"]), "NULL", esc(row["receipt_no"] or None), "NULL",
                "NULL", "NULL", esc("未计算"), esc(row["remark"] or None),
                str(CORP), str(CORP)]) + ")")
        if len(row["combos"]) > 1:
            origin_map.append(f"原始行{row['r']} → {len(row['combos'])} 条：{row['combos']}")
    sql.append("INSERT INTO ops_business_data (" + cols + ") VALUES\n" + ",\n".join(recs) + ";")
    open("/tmp/kangle2025.sql", "w", encoding="utf-8").write("\n".join(sql) + "\n")
    # 报告
    rep = []
    rep.append(f"# 康乐2025 导入报告\n")
    rep.append(f"- 批次号：{BATCH}（corp_id={CORP} 康乐子公司）")
    rep.append(f"- 源数据行数：{len(rows) + len(skipped)}；有效导入行：{len(rows)}；生成记录数：{len(recs)}")
    rep.append(f"- 因手机号非标准跳过：{len(skipped)} 行")
    for r, p in skipped: rep.append(f"  - 原始行 {r}：联系电话=[{p}]")
    rep.append(f"- 会员：匹配现有 {len(rows) - len(created)} 行；新建会员 {len(created)} 个")
    for ph, (nm, ad) in created.items(): rep.append(f"  - {nm} {ph}（地址：{ad or '-'}）")
    rep.append(f"- 多组合拆分（原始行 → 记录数）：{len(origin_map)} 行")
    rep.extend("  - " + o for o in origin_map)
    rep.append(f"- 警告：{len(warnings)} 条")
    rep.extend("  - " + w for w in warnings)
    open("/tmp/kangle2025_report.md", "w", encoding="utf-8").write("\n".join(rep) + "\n")
    print("rows:", len(rows), "skipped:", len(skipped), "records:", len(recs),
          "created:", len(created), "warnings:", len(warnings))
    for r, p in skipped: print("SKIP", r, p)
    for w in warnings: print("WARN", w)
    for o in origin_map: print("MULTI", o)

main()
