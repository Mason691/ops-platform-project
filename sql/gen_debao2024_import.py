# -*- coding: utf-8 -*-
"""德保2024.xls → ops_business_data（品牌跨行/更换为 拆分 + 会员匹配/创建）"""
import xlrd, re, subprocess, collections

BATCH = "德保2024-001"
CORP = 12
BRANDS = ["小松鼠", "万和", "万家乐", "美的", "方太", "华帝", "老板", "樱花", "海尔", "康佳", "林内", "能率"]
CATEGORY_WORDS = ["燃气灶", "热水器", "壁挂炉", "灶具", "抽油烟机", "报警器"]
CAT = {"壁挂炉": "4", "灶具": "1", "燃气灶": "1", "居民燃气灶": "1", "热水器": "3"}
QTY_RE = re.compile(r"[ ]+(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)?\s*$")
SEP_RE = re.compile(r"[、\n\r；;]+")

def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", "mysql", "mysql", "-uroot", "-padmin.2026",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, text=True)
    if out.returncode != 0:
        print("MYSQL ERR:", out.stderr[:300]); raise SystemExit(1)
    return out.stdout

def cell(sh, r, c, dm):
    c_ = sh.cell(r, c); v = c_.value
    if c_.ctype == 3:
        try: v = xlrd.xldate_as_datetime(v, dm).strftime("%Y-%m-%d")
        except Exception: pass
    elif c_.ctype == 2:
        f = float(v); v = int(f) if f.is_integer() else f
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

def cat_of(seg_text, row_cat):
    for word, key in (("壁挂炉", "4"), ("灶具", "1"), ("燃气灶", "1"), ("热水器", "3")):
        if word in seg_text: return key
    return row_cat

def leading_brand(seg):
    for b in BRANDS:
        if seg.startswith(b):
            rest = seg[len(b):].strip()
            for cw in CATEGORY_WORDS:
                if rest.startswith(cw): rest = rest[len(cw):].strip()
            return b, rest.lstrip("：: ")
    return "", seg

def split_row(row, warnings, notes):
    cell_text, total_qty, price_cell = row["cell9"], row["total_qty"], row["price_cell"]
    row_cat = CAT.get(row["category"], row["category"])
    segs_raw = [s.strip().rstrip("、") for s in SEP_RE.split(cell_text) if s.strip()]
    combos = []
    if not segs_raw:
        return [("", "", 1, row_cat)]
    pending_brand = ""
    for seg in segs_raw:
        if seg in BRANDS:            # 纯品牌段 → 下一段品牌前缀（如「万和⏎型号」）
            pending_brand = seg; continue
        qty = None
        m = QTY_RE.search(seg)
        if m:
            q = float(m.group(1)); qty = int(q) if q == int(q) else q
            seg = seg[:m.start()].strip()
        brand, rest = leading_brand(seg)
        if not brand: brand = pending_brand
        pending_brand = ""
        if "更换" in seg or "换为" in seg:
            tail = re.split(r"更换为?|换为", seg)[-1].strip()
            if not tail:
                warnings.append(f"第{row['r']}行：含更换/原有标注的旧型号段跳过 [{seg}]")
                continue
            tb, trest = leading_brand(tail)   # 新机型可能自带品牌
            if tb: brand, rest = tb, trest
            else: rest = tail
            seg_full = tail                   # 类别推断用新机型文本
        else:
            seg_full = seg
        rest = re.sub(r"(补差价|补差)$", "", rest).strip()
        if rest == "" and brand == "" :
            cm = re.match(r"^([^\d：:]+)[：:]\s*(.+)$", seg)
            if cm and seg not in ("可燃气体报警器", "商业报警器", "居民报警器", "工商业报警器"):
                brand, rest = cm.group(1).strip(), cm.group(2).strip()
        combos.append((brand, rest if rest else "", qty, seg_full))
    # 单价配对
    price_segs = [s.strip() for s in SEP_RE.split(price_cell) if s.strip()]
    pq = []
    for ps in price_segs:
        m1 = re.match(r"^(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)(\d+(?:\.\d+)?)", ps)
        if m1: pq.append((float(m1.group(1)), float(m1.group(3)))); continue
        m2 = re.match(r"^(\d+(?:\.\d+)?)\*(\d+(?:\.\d+)?)", ps)
        if m2: pq.append((float(m2.group(2)), float(m2.group(1)))); continue
        m3 = re.match(r"^(\d+(?:\.\d+)?)", ps)
        if m3: pq.append((None, float(m3.group(1)))); continue
        pq.append((None, None))
    tq = None
    try: tq = int(float(total_qty)) if total_qty else None
    except (ValueError, TypeError): pass
    resolved = []
    for i, (b, mo, q, seg) in enumerate(combos):
        if q is None:
            if len(price_segs) > 1 and len(pq) == len(combos) and pq[i][0] is not None:
                q = pq[i][0]; q = int(q) if q == int(q) else q
            elif len(combos) == 1:
                q = tq if tq is not None else 1
            else:
                q = 1
                if i == 0:
                    notes.append(f"第{row['r']}行：组合销售无逐型号数量，默认每组合 1 [{cell_text}]")
        resolved.append((b, mo, q, cat_of(seg, row_cat)))
    return resolved

def main():
    wb = xlrd.open_workbook("/tmp/debao2024.xls")
    sh = wb.sheet_by_index(0)
    dm = wb.datemode
    rows, skipped = [], []
    for r in range(2, sh.nrows):
        v = {c: cell(sh, r, c, dm) for c in range(29)}
        if not any(v[c] for c in (3, 5, 2, 9, 13, 15, 17, 19)): continue
        m = re.search(r"1[3-9]\d{9}", v[5])
        if not m:
            skipped.append((f"行{r}", v[5] or "(空)")); continue
        phone = m.group(0)
        # 双收据单元格取首个
        rec = re.findall(r"\d{5,}", v[14])
        receipt_no = rec[0] if rec else (re.findall(r"\d{5,}", v[18])[0] if v[18] else None)
        rows.append(dict(r=r, phone=phone,
            name="".join([ln.strip() for ln in v[3].splitlines()]),
            address=v[4], category=v[2], cell9=v[9], total_qty=v[10],
            price_cell=v[11], receipt=norm_date(v[13]) or norm_date(v[17]),
            receipt_no=receipt_no, price=v[15] or v[19],
            remark=("合同号:" + v[1] + " " + v[28]).strip() if v[1] else v[28],
            g2=v[17] or v[19]))
    # 会员
    phone2id = {}
    for line in mysql("SELECT phone, id FROM ai_platform.sys_member WHERE delete_flag=0 AND phone IS NOT NULL").splitlines():
        p = line.split("\t")
        if len(p) == 2: phone2id[p[0]] = int(p[1])
    name2id = {}
    for line in mysql("SELECT id, real_name, nickname FROM ai_platform.sys_member WHERE delete_flag=0 AND corp_id=%d" % CORP).splitlines():
        p = line.split("\t")
        if len(p) == 3:
            for nm in (p[1], p[2]):
                if nm and nm not in name2id: name2id[nm] = int(p[0])
    created = {}
    for row in rows:
        mid = phone2id.get(row["phone"]) or (name2id.get(row["name"]) if row["name"] in name2id else None)
        row["member"] = mid
        if mid is None and row["phone"] not in created:
            created[row["phone"]] = (row["name"], row["address"])
    warnings, notes, origin_map = [], [], []
    for row in rows:
        row["combos"] = split_row(row, warnings, notes)
        if len(row["combos"]) > 1:
            origin_map.append(f"行{row['r']} → {len(row['combos'])} 条：{row['combos']}")
    sql = ["USE mall_db;"]
    if created:
        mem_rows = ["(" + ",".join([esc(ph), esc(nm), esc(nm), "1",
            esc("业务数据导入自动创建"), esc(ad or None), str(CORP)]) + ")"
            for ph, (nm, ad) in created.items()]
        sql.append("INSERT INTO ai_platform.sys_member (phone, nickname, real_name, status, remark, address, corp_id) VALUES\n"
                   + ",\n".join(mem_rows) + ";")
    cols = ("batch_no,company,customer,customer_phone,platform_member_id,brand,category,model,"
            "sale_quantity,sale_price,agent,agent_phone,is_special,commission_rate,sale_date,"
            "receipt_date,finance_receipt_date,fund_dynamic_rows,receipt_no,department,"
            "retail_floor_price,group_lowest_price,calc_status,remark,corp_id,operator_id")
    recs = []
    for row in rows:
        mid = str(row["member"]) if row["member"] is not None else (
            "(SELECT id FROM ai_platform.sys_member WHERE phone = '%s' AND delete_flag = 0 LIMIT 1)" % row["phone"])
        for b, mo, q, cat in row["combos"]:
            recs.append("(" + ",".join([
                esc(BATCH), esc("德保"), esc(row["name"]), esc(row["phone"]),
                mid, esc(b or None), esc(cat), esc(mo or None),
                str(q) if q is not None else "NULL", esc(row["price"]),
                "NULL", "NULL", esc("否"), "0",
                esc(row["receipt"]), esc(row["receipt"]), esc(row["receipt"]),
                "NULL", esc(row["receipt_no"] or None), "NULL",
                "NULL", "NULL", esc("未计算"), esc(row["remark"] or None),
                str(CORP), str(CORP)]) + ")")
    sql.append("INSERT INTO ops_business_data (" + cols + ") VALUES\n" + ",\n".join(recs) + ";")
    open("/tmp/debao2024.sql", "w", encoding="utf-8").write("\n".join(sql) + "\n")
    rep = []
    rep.append("# 德保2024 导入报告\n")
    rep.append(f"- 批次号：{BATCH}（corp_id={CORP} 德保子公司）")
    rep.append(f"- 源数据行：{len(rows)+len(skipped)}；有效行：{len(rows)}；生成记录数：{len(recs)}")
    rep.append(f"- 手机号非标准跳过：{len(skipped)} 行")
    for r, p in skipped: rep.append(f"  - {r}：联系电话=[{p}]")
    rep.append(f"- 会员：新建 {len(created)} 个")
    for ph, (nm, ad) in created.items(): rep.append(f"  - {nm} {ph}（地址：{ad or '-'}）")
    rep.append(f"- 多组合拆分：{len(origin_map)} 行")
    rep.extend("  - " + o for o in origin_map)
    g2_rows = [f"行{row['r']} g2={row['g2']}" for row in rows if row["g2"]]
    rep.append(f"- 第二收款组（定金/尾款小票，未单独入库）：{len(g2_rows)} 行")
    rep.extend("  - " + x for x in g2_rows)
    rep.append(f"- 提示：{len(notes)} 条")
    rep.extend("  - " + n for n in notes)
    rep.append(f"- 警告：{len(warnings)} 条")
    rep.extend("  - " + w for w in warnings)
    open("/tmp/debao2024_report.md", "w", encoding="utf-8").write("\n".join(rep) + "\n")
    print("rows:", len(rows), "records:", len(recs), "created:", len(created),
          "skipped:", len(skipped), "warnings:", len(warnings), "notes:", len(notes),
          "g2rows:", len(g2_rows))
    for r, p in skipped: print("SKIP", r, p)
    for w in warnings: print("WARN", w)
    for n in notes: print("NOTE", n)
    for o in origin_map: print("MULTI", o)

main()
