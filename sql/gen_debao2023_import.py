# -*- coding: utf-8 -*-
"""德保2023.xls → ops_business_data（29 列双收款组；无电话行查 ops_gas_user）"""
import xlrd, re, subprocess, collections

BATCH = "德保2023-001"
CORP = 12
BRANDS = ["小松鼠", "万和", "万家乐", "美的", "方太", "华帝", "老板", "樱花", "海尔", "康佳", "林内", "能率"]
CATEGORY_WORDS = ["燃气灶", "热水器", "壁挂炉", "灶具", "抽油烟机", "油烟机", "报警器"]
CAT = {"壁挂炉": "4", "壁挂炉（退款）": "4", "壁挂炉（退费）": "4", "灶具": "1", "燃气灶": "1",
       "民用燃气灶": "1", "居民燃气灶": "1", "热水器": "3", "万和热水器": "3", "燃气热水器": "3",
       "油烟机": "2", "抽油烟机": "2"}
QTY_RE = re.compile(r"[ ]+(\d+(?:\.\d+)?)\s*(台|个|套|只|部|支|项|组|件)?\s*$")
SEP_RE = re.compile(r"[、\n\r；;/]+")

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

def num(v):
    try:
        f = float(str(v).replace(",", ""))
        return int(f) if f.is_integer() else f
    except (ValueError, TypeError):
        return None

def split_row(cell_text, total_qty, row_cat, warnings, notes, row_id):
    segs_raw = [s.strip().rstrip("、").rstrip("）()") for s in SEP_RE.split(cell_text) if s.strip()]
    combos = []
    if not segs_raw:
        tq0 = num(total_qty)
        return [("", "", (tq0 if tq0 is not None else None), row_cat)]
    if "元" in cell_text:
        for ch in re.split(r"[ \n\r]+", cell_text):
            ch = ch.strip()
            if not ch: continue
            mo = ch.split("/")[0].strip()
            mq = re.search(r"(\d+(?:\.\d+)?)\s*台", ch)
            q = float(mq.group(1)) if mq else None
            if q is not None and q == int(q): q = int(q)
            combos.append(("", mo if mo else "", q, ch))
        return [(b, mo, q, row_cat) for b, mo, q, _ in combos]
    CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    pending_brand = ""
    for seg in segs_raw:
        if seg in BRANDS:
            pending_brand = seg; continue
        mcn = re.match(r"^([一二三四五六七八九十]+)\s*[个台套只]?\s*(.*)$", seg)
        if mcn and not re.search(r"[A-Za-z0-9]", seg):
            combos.append(("", mcn.group(2).strip() or "", CN_NUM.get(mcn.group(1), 1), seg)); continue
        mnum = re.match(r"^(\d+(?:\.\d+)?)\s*[个台套只]\s*(.*)$", seg)
        if mnum and not re.search(r"[A-Za-z]", seg):
            q = float(mnum.group(1)); q = int(q) if q == int(q) else q
            combos.append(("", mnum.group(2).strip() or "", q, seg)); continue
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
                warnings.append(f"{row_id}：含更换/原有标注的旧型号段跳过 [{seg}]")
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
        rest = re.sub(r"(补差价|补差)$", "", rest).strip().rstrip("）()")
        if brand and not rest:
            pending_brand = brand
            continue
        combos.append((brand, rest if rest else "", qty, seg))
    if not combos and pending_brand:
        combos = [(pending_brand, "", None, pending_brand)]
    tq = num(total_qty)
    resolved = []
    for i, (b, mo, q, seg) in enumerate(combos):
        if q is None:
            if len(combos) == 1:
                q = tq
                if q is None:
                    notes.append(f"{row_id}：单型号无台数（批量/机构行），数量置空")
            elif tq is not None and tq == len(combos):
                q = 1
                if i == 0: notes.append(f"{row_id}：{len(combos)} 个型号均分合同总台数 {tq}（每组合 1）")
            elif tq is not None:
                if i == 0: warnings.append(f"{row_id}：多型号且台数({tq})≠型号数({len(combos)})，数量置空 [{cell_text}]")
            else:
                q = 1
                if i == 0: notes.append(f"{row_id}：组合销售无逐型号数量，默认每组合 1")
        resolved.append((b, mo, q, row_cat))
    return resolved

def main():
    wb = xlrd.open_workbook("/tmp/debao2023.xls")
    sh = wb.sheet_by_index(0)
    dm = wb.datemode
    gas = {}
    for line in mysql("SELECT id, full_name, phone, platform_member_id FROM mall_db.ops_gas_user WHERE delete_flag=0 AND corp_id=%d" % CORP).splitlines():
        p = line.split("\t")
        if len(p) != 4: continue
        gid, nm, ph, mid = (int(p[0]) if p[0] != 'NULL' else 0), p[1], (p[2] if p[2] != 'NULL' else ''), (int(p[3]) if p[3] and p[3] != 'NULL' else None)
        if nm not in gas: gas[nm] = (ph, mid, gid)
        else:
            old = gas[nm]
            if (old[1] is None and mid is not None) or (old[1] == mid and gid < old[2]):
                gas[nm] = (ph, mid, gid)
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
    rows, warnings, notes, created = [], [], [], {}
    for r in range(2, sh.nrows):
        v = {c: cell(sh, r, c, dm, as_date=(c in (13, 17))) for c in range(29)}
        if not any(v.get(c) for c in (2, 3, 5, 9, 13, 15, 17, 19)): continue
        name = "".join([ln.strip() for ln in v[3].splitlines()])
        if not name:
            continue
        m = re.search(r"1[3-9]\d{9}", v[5])
        phone = m.group(0) if m else ""
        if not phone:
            g = gas.get(name)
            if g and g[0] and re.match(r"^1[3-9]\d{9}$", g[0]):
                phone = g[0]; notes.append(f"行{r}：联系电话缺失，燃气用户({name})查得 {phone}")
            elif g and g[0]:
                notes.append(f"行{r}：联系电话缺失，燃气用户({name})电话 [{g[0]}] 非 11 位不可用")
            else:
                notes.append(f"行{r}：联系电话缺失且燃气用户无匹配，按姓名匹配或创建无电话会员")
        mid = phone2id.get(phone) if phone else None
        if mid is None and phone:
            g = gas.get(name)
            if g and g[1]: mid = g[1]
        if mid is None and name in name2id:
            mid = name2id[name]
        ckey = phone or ("N:" + name)
        if mid is None and ckey not in created:
            created[ckey] = (phone or None, name, v[4])
        rec = re.findall(r"\d{5,}", v[14]) or re.findall(r"\d{5,}", v[18])
        receipt = norm_date(v[13]) or norm_date(v[17])
        price = v[15] or v[19]
        if v[17] or v[19]:
            notes.append(f"行{r}：存在第二收款组（{v[17]} {v[18]} {v[19]}），取第一组入库")
        rows.append(dict(r=r, phone=phone, name=name, category=v[2], cell9=v[9],
                         total_qty=v[10], receipt=receipt, receipt_no=rec[0] if rec else None,
                         price=price, remark=("合同号:" + v[1] + " " + v[28]).strip() if v[1] else v[28],
                         member=mid))
    for row in rows:
        row_cat = CAT.get(row["category"], row["category"])
        row["combos"] = split_row(row["cell9"], row["total_qty"], row_cat, warnings, notes, f"行{row['r']}")
    sql = ["USE mall_db;"]
    if created:
        mem_rows = ["(" + ",".join([esc(ph), esc(nm), esc(nm), "1",
            esc("业务数据导入自动创建"), esc(ad or None), str(CORP)]) + ")"
            for ck, (ph, nm, ad) in created.items()]
        sql.append("INSERT INTO ai_platform.sys_member (phone, nickname, real_name, status, remark, address, corp_id) VALUES\n"
                   + ",\n".join(mem_rows) + ";")
    cols = ("batch_no,company,customer,customer_phone,platform_member_id,brand,category,model,"
            "sale_quantity,sale_price,agent,agent_phone,is_special,commission_rate,sale_date,"
            "receipt_date,finance_receipt_date,fund_dynamic_rows,receipt_no,department,"
            "retail_floor_price,group_lowest_price,calc_status,remark,corp_id,operator_id")
    recs, origin_map = [], []
    for row in rows:
        if row["member"] is not None:
            mid = str(row["member"])
        elif row["phone"]:
            mid = "(SELECT id FROM ai_platform.sys_member WHERE phone = '%s' AND delete_flag = 0 LIMIT 1)" % row["phone"]
        else:
            mid = ("(SELECT id FROM ai_platform.sys_member WHERE real_name = '%s' AND corp_id = %d "
                   "AND remark = '业务数据导入自动创建' AND delete_flag = 0 ORDER BY id DESC LIMIT 1)"
                   % (row["name"].replace("'", "''"), CORP))
        for b, mo, q, cat in row["combos"]:
            recs.append("(" + ",".join([
                esc(BATCH), esc("德保"), esc(row["name"]), esc(row["phone"] or None),
                mid, esc(b or None), esc(cat), esc(mo or None),
                str(q) if q is not None else "NULL", esc(str(row["price"]) if row["price"] is not None else None),
                "NULL", "NULL", esc("否"), "0",
                esc(row["receipt"]), esc(row["receipt"]), esc(row["receipt"]),
                "NULL", esc(row["receipt_no"] or None), "NULL",
                "NULL", "NULL", esc("未计算"), esc(row["remark"] or None),
                str(CORP), str(CORP)]) + ")")
        if len(row["combos"]) > 1:
            origin_map.append(f"行{row['r']} → {len(row['combos'])} 条：{row['combos']}")
    sql.append("INSERT INTO ops_business_data (" + cols + ") VALUES\n" + ",\n".join(recs) + ";")
    open("/tmp/debao2023.sql", "w", encoding="utf-8").write("\n".join(sql) + "\n")
    rep = []
    rep.append("# 德保2023 导入报告\n")
    rep.append(f"- 批次号：{BATCH}（corp_id={CORP} 德保子公司）")
    rep.append(f"- 源数据行：{len(rows)}；生成记录数：{len(recs)}")
    rep.append(f"- 会员：新建 {len(created)} 个")
    for ck, (ph, nm, ad) in created.items(): rep.append(f"  - {nm} {ph or '(无电话)'}（地址：{ad or '-'}）")
    rep.append(f"- 多组合拆分：{len(origin_map)} 行")
    rep.extend("  - " + o for o in origin_map)
    rep.append(f"- 提示：{len(notes)} 条")
    rep.extend("  - " + n for n in notes)
    rep.append(f"- 警告：{len(warnings)} 条")
    rep.extend("  - " + w for w in warnings)
    open("/tmp/debao2023_report.md", "w", encoding="utf-8").write("\n".join(rep) + "\n")
    print("rows:", len(rows), "records:", len(recs), "created:", len(created),
          "warnings:", len(warnings), "notes:", len(notes))
    for w in warnings: print("WARN", w)
    for o in origin_map: print("MULTI", o)

main()
