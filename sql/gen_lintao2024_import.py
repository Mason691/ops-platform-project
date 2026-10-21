# -*- coding: utf-8 -*-
"""临洮2024.xls → ops_business_data（52/13 列双表去重 + 上下文合并 + 拆分 + 会员匹配/创建）"""
import xlrd, re, subprocess, collections

BATCH = "临洮2024-001"
CORP = 9
BRANDS = ["小松鼠", "万和", "万家乐", "美的", "方太", "华帝", "老板", "樱花", "海尔", "康佳", "林内", "能率"]
CATEGORY_WORDS = ["燃气灶", "热水器", "壁挂炉", "灶具", "抽油烟机", "报警器"]
CAT = {"壁挂炉": "4", "灶具": "1", "燃气灶": "1", "居民燃气灶": "1", "热水器": "3", "燃气热水器": "3"}
QTY_RE = re.compile(r"[ ]+(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)?\s*$")
SEP_RE = re.compile(r"[、\n\r；;]+")
NOTE_KW = ("送", "退款", "退", "保险")

def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", "mysql", "mysql", "-uroot", "-padmin.2026",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, text=True)
    if out.returncode != 0:
        print("MYSQL ERR:", out.stderr[:300]); raise SystemExit(1)
    return out.stdout

def cell(sh, r, c, dm, as_date=False):
    c_ = sh.cell(r, c); v = c_.value
    if c_.ctype == 3 or (as_date and c_.ctype == 2 and 20000 < float(v) < 80000):
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

def map_cat(raw):
    base = raw.strip()
    base = re.sub(r"（.*?）$", "", base).rstrip("款")
    return CAT.get(base, raw)

def cat_of(seg_text, row_cat):
    for word, key in (("壁挂炉", "4"), ("灶具", "1"), ("燃气灶", "1"), ("热水器", "3")):
        if word in seg_text: return key
    return row_cat

def split_row(row, warnings, notes):
    cell_text, total_qty, price_cell = row["cell9"], row["total_qty"], row["price_cell"]
    row_cat = map_cat(row["category"])
    segs_raw = [s.strip().rstrip("、").rstrip("）()") for s in SEP_RE.split(cell_text) if s.strip()]
    combos = []
    if not segs_raw:
        return [("", "", 1, row_cat)]
    pending_brand = ""
    for seg in segs_raw:
        if seg in BRANDS:
            pending_brand = seg; continue
        qty = None
        m = QTY_RE.search(seg)
        if m:
            q = float(m.group(1)); qty = int(q) if q == int(q) else q
            seg = seg[:m.start()].strip()
        brand, rest = "", seg
        for b in BRANDS:
            if seg.startswith(b):
                brand = b
                r2 = seg[len(b):].strip()
                for cw in CATEGORY_WORDS:
                    if r2.startswith(cw): r2 = r2[len(cw):].strip()
                rest = r2.lstrip("：: ")
                break
        if not brand: brand = pending_brand
        pending_brand = ""
        if "更换" in seg or "换为" in seg:
            tail = re.split(r"更换为?|换为", seg)[-1].strip()
            if not tail:
                warnings.append(f"第{row['src']}-{row['r']}行：含更换/原有标注的旧型号段跳过 [{seg}]")
                continue
            tb, trest = "", tail
            for b in BRANDS:
                if tail.startswith(b):
                    tb = b; trest = tail[len(b):].strip()
                    for cw in CATEGORY_WORDS:
                        if trest.startswith(cw): trest = trest[len(cw):].strip()
                    break
            if tb: brand, rest = tb, trest.lstrip("：: ")
            else: rest = tail
            seg_full = tail
        else:
            seg_full = seg
        rest = re.sub(r"(补差价|补差)$", "", rest).strip().rstrip("）()")
        combos.append((brand, rest if rest else "", qty, seg_full))
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
                    if tq is not None and tq == len(combos):
                        notes.append(f"第{row['src']}-{row['r']}行：{len(combos)} 个型号均分合同总台数 {tq}（每组合 1）")
                    else:
                        notes.append(f"第{row['src']}-{row['r']}行：组合销售无逐型号数量，默认每组合 1 [{cell_text}]")
        resolved.append((b, mo, q, cat_of(seg, row_cat)))
    return resolved

def parse_rows(sh, dm, sheet_no, rows_out, skipped, notes):
    if sheet_no == 1:   # 52 列合同台账：name2 addr3 phone4 cat8 cell9 tq10 pc11 date13 rec14 amt15 remark51
        for r in range(2, sh.nrows):
            v = {c: cell(sh, r, c, dm, as_date=(c == 13)) for c in range(52)}
            if not any(v.get(c) for c in (2, 4, 8, 9, 13, 15)): continue
            m = re.search(r"1[3-9]\d{9}", v.get(4, ""))
            if not m:
                skipped.append((f"sheet1行{r}", v.get(4) or "(空)")); continue
            rows_out.append(dict(src="s1", r=r, phone=m.group(0),
                name="".join([ln.strip() for ln in v[2].splitlines()]),
                address=v[3], category=v[8], cell9=v[9], total_qty=v[10],
                price_cell=v[11], receipt=norm_date(v[13]), receipt_no=v[14],
                price=v[15], remark=v[51], agent=None))
    else:                # 13 列散户台账：name1 xq2 addr3 phone4 rec5 date6 amt7 cat8 remark9 cell10 col11 混装(经办人/赠品/数量)
        for r in range(1, sh.nrows):
            v = {c: cell(sh, r, c, dm, as_date=(c == 6)) for c in range(13)}
            if not any(v.get(c) for c in (1, 4, 5, 7, 10)): continue
            m = re.search(r"1[3-9]\d{9}", v.get(4, ""))
            if not m:
                skipped.append((f"sheet2行{r}", v.get(4) or "(空)")); continue
            extra, agent, tq = v[11], None, ""
            if extra:
                if re.match(r"^\d+$", extra):
                    tq = extra
                elif any(kw in extra for kw in NOTE_KW) or re.search(r"\d", extra):
                    notes.append(f"sheet2行{r}：第12列备注并入 remark [{extra}]")
                    v[9] = (v[9] + " " + extra).strip() if v[9] else extra
                else:
                    agent = extra
            rows_out.append(dict(src="s2", r=r, phone=m.group(0),
                name="".join([ln.strip() for ln in v[1].splitlines()]),
                address="、".join(x for x in (v[2], v[3]) if x),
                category=v[8], cell9=v[10], total_qty=tq, price_cell="",
                receipt=norm_date(v[6]), receipt_no=v[5], price=v[7],
                remark=v[9], agent=agent))

def main():
    wb = xlrd.open_workbook("/tmp/lintao2024.xls")
    dm = wb.datemode
    s2_rows, s1_rows, skipped, notes = [], [], [], []
    parse_rows(wb.sheet_by_index(1), dm, 2, s2_rows, skipped, notes)
    parse_rows(wb.sheet_by_index(0), dm, 1, s1_rows, skipped, notes)
    s1_by_rec = {}
    for row in s1_rows:
        if row["receipt_no"] and row["receipt_no"] not in s1_by_rec:
            s1_by_rec[row["receipt_no"]] = row
    seen, kept, dup_cnt = set(), [], 0
    for row in s2_rows + s1_rows:
        key = row["receipt_no"] if row["receipt_no"] else None
        if key:
            if key in seen: dup_cnt += 1; continue
            seen.add(key)
            if row["src"] == "s2" and not row["total_qty"] and key in s1_by_rec:
                c = s1_by_rec[key]
                row["total_qty"], row["price_cell"] = c["total_qty"], c["price_cell"]
        else:
            k2 = (row["phone"], row["receipt"] or "", row["price"] or "", row["cell9"])
            if k2 in seen: dup_cnt += 1; continue
            seen.add(k2)
        kept.append(row)
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
    for row in kept:
        mid = phone2id.get(row["phone"]) or (name2id.get(row["name"]) if row["name"] in name2id else None)
        row["member"] = mid
        if mid is None and row["phone"] not in created:
            created[row["phone"]] = (row["name"], row["address"])
    warnings, origin_map = [], []
    for row in kept:
        row["combos"] = split_row(row, warnings, notes)
        if len(row["combos"]) > 1:
            origin_map.append(f"{row['src']}行{row['r']} → {len(row['combos'])} 条：{row['combos']}")
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
    for row in kept:
        mid = str(row["member"]) if row["member"] is not None else (
            "(SELECT id FROM ai_platform.sys_member WHERE phone = '%s' AND delete_flag = 0 LIMIT 1)" % row["phone"])
        for b, mo, q, cat in row["combos"]:
            recs.append("(" + ",".join([
                esc(BATCH), esc("临洮"), esc(row["name"]), esc(row["phone"]),
                mid, esc(b or None), esc(cat), esc(mo or None),
                str(q) if q is not None else "NULL", esc(row["price"]),
                esc(row["agent"] or None), "NULL", esc("否"), "0",
                esc(row["receipt"]), esc(row["receipt"]), esc(row["receipt"]),
                "NULL", esc(row["receipt_no"] or None), "NULL",
                "NULL", "NULL", esc("未计算"), esc(row["remark"] or None),
                str(CORP), str(CORP)]) + ")")
    sql.append("INSERT INTO ops_business_data (" + cols + ") VALUES\n" + ",\n".join(recs) + ";")
    open("/tmp/lintao2024.sql", "w", encoding="utf-8").write("\n".join(sql) + "\n")
    rep = []
    rep.append("# 临洮2024 导入报告\n")
    rep.append(f"- 批次号：{BATCH}（corp_id={CORP} 临洮子公司）")
    rep.append(f"- 两 sheet 有效行：{len(s1_rows)+len(s2_rows)}；去重剔除：{dup_cnt}；最终行：{len(kept)}；生成记录数：{len(recs)}")
    rep.append(f"- 手机号非标准跳过：{len(skipped)} 行")
    for r, p in skipped: rep.append(f"  - {r}：联系电话=[{p}]")
    rep.append(f"- 会员：新建 {len(created)} 个")
    for ph, (nm, ad) in created.items(): rep.append(f"  - {nm} {ph}（地址：{ad or '-'}）")
    rep.append(f"- 多组合拆分：{len(origin_map)} 行")
    rep.extend("  - " + o for o in origin_map)
    rep.append(f"- 提示：{len(notes)} 条")
    rep.extend("  - " + n for n in notes)
    rep.append(f"- 警告：{len(warnings)} 条")
    rep.extend("  - " + w for w in warnings)
    open("/tmp/lintao2024_report.md", "w", encoding="utf-8").write("\n".join(rep) + "\n")
    print("s1:", len(s1_rows), "s2:", len(s2_rows), "dup:", dup_cnt, "kept:", len(kept),
          "records:", len(recs), "created:", len(created), "skipped:", len(skipped),
          "warnings:", len(warnings), "notes:", len(notes))
    for r, p in skipped: print("SKIP", r, p)
    for w in warnings: print("WARN", w)
    for n in notes: print("NOTE", n)
    for o in origin_map: print("MULTI", o)

main()
