package com.aip.ops.imports;

import com.aip.ops.dto.ModelConfirmDecision;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessModelAlias;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessModelAliasMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.sdk.client.PlatformInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务数据导入处理器（两段式）。
 * <p>
 * 阶段一 {@link #check}：解析 + 逐行校验 + 品牌型号匹配解析，产出需要人工确认的
 * 模糊型号匹配项（不写库）；阶段二 {@link #confirm}：应用确认决策后全量校验
 * （收据号组内一致性 + 收据号唯一性），全部通过后在单事务内 JDBC batch 原子写入。
 * 任一行不合格则整个文件拒绝，不写入任何数据。
 * </p>
 * <p>品牌型号匹配顺序（非特批 + 核心四品类）：精确匹配 → 已确认别名 →
 * 模糊唯一/多候选（待人工确认）→ 无匹配（失败）。</p>
 */
@Slf4j
@Component
public class BusinessDataImportHandler implements ImportHandler {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsBusinessModelAliasMapper aliasMapper;
    private final OpsPersonalCommissionRuleMapper commissionRuleMapper;
    private final PlatformInternalClient platformInternalClient;
    private final DataSource dataSource;

    private static final String CATEGORY_TYPE_CODE = "PRODUCT_CATEGORY";
    private static final Set<String> VALID_COMPANIES =
            Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");
    /** 核心四品类 itemKey：燃气灶(1)、抽油烟机(2)、热水器(3)、壁挂炉(4) */
    private static final Set<String> CORE_FOUR_CATEGORIES = Set.of("1", "2", "3", "4");
    /** 确认项 key 分隔符（品牌/品类/关键词不含该字符） */
    private static final String KEY_SEP = "\u0001";

    private volatile Set<String> categoryNameCache;
    private volatile Map<String, String> categoryNameToKeyCache;
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "燃气灶", "抽油烟机", "热水器", "壁挂炉",
            "壁挂炉以旧换新销售", "户内美装销售", "定尺波纹管",
            "户内美装工程", "户内美装运维", "保险",
            "美装管道整改", "美装安全评估"
    );

    public BusinessDataImportHandler(OpsBusinessDataMapper mapper,
                                     OpsValueAddedProductMapper productMapper,
                                     OpsBusinessModelAliasMapper aliasMapper,
                                     OpsPersonalCommissionRuleMapper commissionRuleMapper,
                                     PlatformInternalClient platformInternalClient,
                                     DataSource dataSource) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.aliasMapper = aliasMapper;
        this.commissionRuleMapper = commissionRuleMapper;
        this.platformInternalClient = platformInternalClient;
        this.dataSource = dataSource;
    }

    @Override
    public String getTaskType() { return "business_data"; }

    // ── ParsedRow holder ──
    private static class ParsedRow {
        String company, customer, brand, model, catCn, catKey;
        BigDecimal salePrice;
        String agent, agentPhone, dept;
        String saleDate, receiptDate, receiptNo;
        String receiptDateRaw; // raw value for validation (市场收款日期：空=错误)
        String financeReceiptDateRaw, financeReceiptDate;
        String fundDynamicRowsRaw;
        Integer fundDynamicRows;
        OpsValueAddedProduct product;
        int rowNum;                 // 文件行号（表头=1，数据行从 2 起）
        boolean special;            // 是否特批 = "是"
        BigDecimal commissionRate;  // 提成比例（%），特批行导入时落库 commission_rate
    }

    /** 解析准备产物：一次解析/校验的完整上下文（check 与 confirm 各自独立解析） */
    private static class Prepared {
        List<ParsedRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int totalRows;
        List<OpsValueAddedProduct> prods = List.of();
        Map<String, OpsValueAddedProduct> productMap = Map.of();
        Map<String, OpsValueAddedProduct> aliasProductByKey = Map.of();
        String fileMd5;
    }

    /** 待人工确认的模糊匹配项：品牌+品类+型号关键词 → 候选产品列表 */
    private static class PendingItem {
        String key;
        String brand;
        String catKey;
        String catCn;
        String modelKeyword;
        List<Integer> rowNums = new ArrayList<>();
        List<OpsValueAddedProduct> candidates = List.of();
    }

    /** 匹配解析结果 */
    private static class MatchResult {
        Map<ParsedRow, OpsValueAddedProduct> resolved = new IdentityHashMap<>();
        List<PendingItem> pending = new ArrayList<>();
        List<ParsedRow> failRows = new ArrayList<>();
        int exact;
        int alias;
        int specialSkipped;

        int pendingRowCount() {
            return pending.stream().mapToInt(i -> i.rowNums.size()).sum();
        }
    }

    // ═══════ 入口 ═══════

    /**
     * Kafka 异步兜底路径（无交互确认）：等价于零决策的 confirm ——
     * 存在需确认的模糊匹配时整文件拒绝，提示走页面两段式导入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult execute(SysImportTask task) throws Exception {
        String fileName = task.getFileName() != null
                ? task.getFileName().toLowerCase() : "";
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("文件格式不正确，仅支持 .xlsx / .xls"), 0);
        }
        Path filePath = Path.of(task.getFilePath());
        Long corpId = task.getCorpId() != null ? task.getCorpId() : 4L;
        return confirm(filePath, corpId, List.of(), null);
    }

    /** 阶段一：解析 + 校验 + 匹配解析，返回 fail / confirm / ready（不写库）。 */
    public Map<String, Object> check(Path filePath, Long corpId) throws Exception {
        Prepared p = prepare(filePath, corpId);
        if (!p.errors.isEmpty()) return failResponse(p.errors, p.totalRows);

        MatchResult m = resolveMatches(p);
        List<String> matchErrors = unmatchedErrors(m);
        if (!matchErrors.isEmpty()) return failResponse(matchErrors, p.totalRows);
        if (!m.pending.isEmpty()) return confirmResponse(p, m);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ready");
        resp.put("fileMd5", p.fileMd5);
        resp.put("totalRows", p.totalRows);
        resp.put("stats", statsMap(m));
        return resp;
    }

    /** 阶段二：应用确认决策 → 全量校验（收据组/唯一性）→ 单事务原子入库。 */
    public ImportResult confirm(Path filePath, Long corpId,
            List<ModelConfirmDecision> decisions, String expectedMd5) throws Exception {
        Prepared p = prepare(filePath, corpId);
        if (!p.errors.isEmpty()) return ImportResult.fail("ROW_CHECK", p.errors, p.totalRows);

        // 防两阶段文件不一致：confirm 携带 check 返回的 md5
        if (expectedMd5 != null && !expectedMd5.isBlank()
                && !expectedMd5.equals(p.fileMd5)) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("文件与校验阶段不一致，请重新校验后再导入"), p.totalRows);
        }

        MatchResult m = resolveMatches(p);
        List<String> matchErrors = unmatchedErrors(m);
        if (!matchErrors.isEmpty()) return ImportResult.fail("ROW_CHECK", matchErrors, p.totalRows);

        List<String> errors = applyDecisions(p, m, corpId, decisions);
        if (!errors.isEmpty()) return ImportResult.fail("ROW_CHECK", errors, p.totalRows);

        return doInsert(p, corpId);
    }

    // ═══════ 准备：解析 + 逐行校验 ═══════

    private Prepared prepare(Path filePath, Long corpId) throws Exception {
        Prepared p = new Prepared();
        if (!Files.exists(filePath)) {
            p.errors.add("导入文件不存在，请重新上传");
            return p;
        }
        p.fileMd5 = md5Of(filePath);
        String[] rows = parseJson(filePath, p);
        if (rows == null) return p;
        int[] idx = headerIndices(rows[0].split("\t"), p.errors);
        if (idx == null) return p;
        loadLookups(p);
        parseRows(p, rows, idx, loadValidCategories(),
                loadTieredCommissionCategories(corpId != null ? corpId : 4L));
        return p;
    }

    /** Python 解析 Excel → 行数组（表头行 + 数据行）；失败时写入 p.errors 并返回 null。 */
    private String[] parseJson(Path filePath, Prepared p) throws Exception {
        String json;
        try {
            json = parseExcelWithPython(filePath);
        } catch (Exception e) {
            p.errors.add("Excel 解析失败: " + e.getMessage());
            return null;
        }
        if (json == null || json.isEmpty()) {
            p.errors.add("Excel 文件为空或无法解析");
            return null;
        }
        String[] rows = json.trim().split("\n");
        if (rows.length < 2) {
            p.errors.add("Excel 文件无数据行");
            return null;
        }
        return rows;
    }

    /** 加载产品精确匹配 map 与已确认别名 map（租户过滤由 TenantLineHandler 生效）。 */
    private void loadLookups(Prepared p) {
        p.prods = productMapper.selectList(null);
        Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
        for (OpsValueAddedProduct prod : p.prods) {
            productMap.put(exactKey(prod.getBrand(), prod.getCategory(), prod.getModel()), prod);
        }
        p.productMap = productMap;
        p.aliasProductByKey = loadAliasProductMap(p.prods);
    }

    /** 已确认的型号匹配关系 → 归一化关键词 → 产品（产品已删/停用则忽略该别名）。 */
    private Map<String, OpsValueAddedProduct> loadAliasProductMap(
            List<OpsValueAddedProduct> prods) {
        Map<Long, OpsValueAddedProduct> productById = prods.stream().collect(
                Collectors.toMap(OpsValueAddedProduct::getId, x -> x, (a, b) -> a));
        Map<String, OpsValueAddedProduct> aliasMap = new HashMap<>();
        for (OpsBusinessModelAlias alias : aliasMapper.selectList(null)) {
            OpsValueAddedProduct prod = productById.get(alias.getProductId());
            if (prod != null) {
                aliasMap.put(aliasKey(alias.getBrand(), alias.getCategory(),
                        alias.getModelKeyword()), prod);
            }
        }
        return aliasMap;
    }

    /** 逐行解析 + 校验（品类字典/字段/特批比例），空行跳过，行号从表头+1 起。 */
    private void parseRows(Prepared p, String[] rows, int[] idx,
            Set<String> validCategories, Set<String> tieredCategories) {
        int totalRows = 0;
        for (int i = 1; i < rows.length; i++) {
            String[] cols = rows[i].split("\t", -1);
            if (cols.length < 4) continue;
            totalRows++;
            ParsedRow pr = parseRow(cols, idx);
            if (pr == null) continue; // truly blank row
            pr.rowNum = i + 1;
            checkCategory(pr, i + 1, validCategories, p.errors);
            pr.catKey = mapCategoryKey(pr.catCn);
            validateFields(pr, i + 1, p.errors, tieredCategories);
            p.rows.add(pr);
        }
        p.totalRows = totalRows;
    }

    // ═══════ 品牌型号匹配解析 ═══════

    /** 逐行解析匹配状态（见 resolveRowMatch），返回汇总与待确认项。 */
    private MatchResult resolveMatches(Prepared p) {
        MatchResult m = new MatchResult();
        Map<String, PendingItem> pendingByKey = new LinkedHashMap<>();
        Map<String, List<OpsValueAddedProduct>> candCache = new HashMap<>();
        for (ParsedRow pr : p.rows) {
            resolveRowMatch(p, m, pendingByKey, candCache, pr);
        }
        m.pending = new ArrayList<>(pendingByKey.values());
        return m;
    }

    /**
     * 单行匹配状态解析：
     * 特批/非核心四品类跳过 → 精确匹配 → 已确认别名 → 模糊候选（待确认）→ 失败。
     */
    private void resolveRowMatch(Prepared p, MatchResult m,
            Map<String, PendingItem> pendingByKey,
            Map<String, List<OpsValueAddedProduct>> candCache, ParsedRow pr) {
        if (pr.special || pr.catKey == null
                || !CORE_FOUR_CATEGORIES.contains(pr.catKey)) {
            m.specialSkipped++;
            tryExactMatch(p, pr, m);
            return;
        }
        OpsValueAddedProduct exact = p.productMap.get(
                exactKey(pr.brand, pr.catKey, pr.model));
        if (exact != null) {
            m.resolved.put(pr, exact);
            m.exact++;
            return;
        }
        String kw = normalizeModel(pr.model);
        if (kw.isEmpty()) {
            m.failRows.add(pr);
            return;
        }
        String key = aliasKey(pr.brand, pr.catKey, kw);
        OpsValueAddedProduct aliased = p.aliasProductByKey.get(key);
        if (aliased != null) {
            m.resolved.put(pr, aliased);
            m.alias++;
            return;
        }
        // 同 key 的多行共享一份候选集（避免重复扫描产品表）
        List<OpsValueAddedProduct> cands = candCache.computeIfAbsent(key,
                k -> fuzzyCandidates(p.prods, pr.brand, pr.catKey, kw));
        if (cands.isEmpty()) {
            m.failRows.add(pr);
            return;
        }
        PendingItem item = pendingByKey.computeIfAbsent(key,
                k -> newPendingItem(k, pr, cands));
        item.rowNums.add(pr.rowNum);
    }

    /** 特批/非核心四品类行跳过匹配校验，但仍尝试精确匹配以提取底价（与历史行为一致）。 */
    private void tryExactMatch(Prepared p, ParsedRow pr, MatchResult m) {
        if (pr.catKey == null) return;
        OpsValueAddedProduct exact = p.productMap.get(
                exactKey(pr.brand, pr.catKey, pr.model));
        if (exact != null) m.resolved.put(pr, exact);
    }

    private PendingItem newPendingItem(String key, ParsedRow pr,
            List<OpsValueAddedProduct> cands) {
        PendingItem it = new PendingItem();
        it.key = key;
        it.brand = pr.brand;
        it.catKey = pr.catKey;
        it.catCn = pr.catCn;
        it.modelKeyword = pr.model;
        it.candidates = cands;
        return it;
    }

    /** 同品牌同品类的模糊候选：归一化产品型号包含关键词（原始精确相等已在前面处理）。 */
    private List<OpsValueAddedProduct> fuzzyCandidates(List<OpsValueAddedProduct> prods,
            String brand, String catKey, String kw) {
        List<OpsValueAddedProduct> cands = new ArrayList<>();
        for (OpsValueAddedProduct prod : prods) {
            if (prod.getBrand() == null || prod.getCategory() == null
                    || prod.getModel() == null) continue;
            if (!prod.getBrand().equals(brand)
                    || !prod.getCategory().equals(catKey)) continue;
            String pm = normalizeModel(prod.getModel());
            if (pm.isEmpty()) continue;
            // 归一化相等也是候选（如 "JZT Q08" 与 "JZT-Q08"），多候选时由操作人员确认
            if (pm.equals(kw) || pm.contains(kw)) cands.add(prod);
        }
        cands.sort(Comparator.comparing(OpsValueAddedProduct::getId));
        return cands;
    }

    /** 型号归一化：NFKC 折叠全角 → 去空白/标点等特殊字符 → 转小写（与导出关键字兜底匹配口径一致）。 */
    private String normalizeModel(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String exactKey(String brand, String catKey, String model) {
        return brand + "|" + catKey + "|" + model;
    }

    private String aliasKey(String brand, String catKey, String kw) {
        return brand + KEY_SEP + catKey + KEY_SEP + kw;
    }

    private List<String> unmatchedErrors(MatchResult m) {
        if (m.failRows.isEmpty()) return List.of();
        List<String> errors = new ArrayList<>();
        errors.add("以下数据未找到匹配的品牌型号，请修正后重新导入：");
        for (ParsedRow pr : m.failRows) {
            errors.add("第" + pr.rowNum + "行：品类=" + pr.catCn
                    + "，品牌=" + pr.brand + "，型号=" + pr.model);
        }
        return errors;
    }

    // ═══════ 确认决策应用 ═══════

    /**
     * 应用操作人员的确认决策：命中候选 → 行解析到该产品 + 落别名记忆；
     * 拒绝/未决策 → 对应行报错（连带整文件拒绝）。
     */
    private List<String> applyDecisions(Prepared p, MatchResult m, Long corpId,
            List<ModelConfirmDecision> decisions) {
        List<String> errors = new ArrayList<>();
        Map<String, PendingItem> byKey = new HashMap<>();
        for (PendingItem item : m.pending) byKey.put(item.key, item);

        Map<String, OpsValueAddedProduct> chosen = new HashMap<>();
        for (ModelConfirmDecision d : decisions) {
            if (d == null || d.getKey() == null) continue;
            PendingItem item = byKey.get(d.getKey());
            if (item == null) {
                errors.add("确认项无效或已不存在: " + d.getKey());
                continue;
            }
            if (d.getProductId() == null) continue; // 拒绝：不解析，由下方统一报错
            OpsValueAddedProduct pick = item.candidates.stream()
                    .filter(c -> c.getId().equals(d.getProductId()))
                    .findFirst().orElse(null);
            if (pick == null) {
                errors.add("确认型号不在候选列表（品牌=" + item.brand
                        + "，型号=" + item.modelKeyword + "）");
                continue;
            }
            chosen.put(item.key, pick);
        }
        for (PendingItem item : m.pending) {
            OpsValueAddedProduct prod = chosen.get(item.key);
            if (prod == null) {
                for (int rn : item.rowNums) {
                    errors.add("第" + rn + "行: 型号匹配未确认（品牌=" + item.brand
                            + "，型号=" + item.modelKeyword + "）");
                }
            } else {
                saveAlias(corpId, item, prod);
            }
        }
        applyResolvedProducts(p, m, chosen);
        return errors;
    }

    /** 回填 ParsedRow.product：精确/别名/确认行均取到产品（供 INSERT 提取底价）。 */
    private void applyResolvedProducts(Prepared p, MatchResult m,
            Map<String, OpsValueAddedProduct> chosen) {
        for (Map.Entry<ParsedRow, OpsValueAddedProduct> e : m.resolved.entrySet()) {
            e.getKey().product = e.getValue();
        }
        if (m.pending.isEmpty() || chosen.isEmpty()) return;
        Map<Integer, ParsedRow> byRowNum = new HashMap<>();
        for (ParsedRow pr : p.rows) byRowNum.put(pr.rowNum, pr);
        for (PendingItem item : m.pending) {
            OpsValueAddedProduct prod = chosen.get(item.key);
            if (prod == null) continue;
            for (Integer rn : item.rowNums) {
                ParsedRow pr = byRowNum.get(rn);
                if (pr != null) pr.product = prod;
            }
        }
    }

    /** 确认后的匹配关系落库记忆（唯一键冲突=已存在，幂等忽略）。 */
    private void saveAlias(Long corpId, PendingItem item, OpsValueAddedProduct product) {
        OpsBusinessModelAlias alias = new OpsBusinessModelAlias();
        alias.setCorpId(corpId);
        alias.setBrand(item.brand);
        alias.setCategory(item.catKey);
        alias.setModelKeyword(normalizeModel(item.modelKeyword));
        alias.setProductId(product.getId());
        try {
            aliasMapper.insert(alias);
            log.info("Model alias saved: corp={} {}/{} {} → {}",
                    corpId, item.brand, item.catCn, item.modelKeyword, product.getModel());
        } catch (DataIntegrityViolationException e) {
            // 重复确认/并发时唯一键冲突：别名已存在，忽略
            log.debug("Model alias already exists: {}/{}", item.brand, item.modelKeyword);
        }
    }

    // ═══════ 校验阶段响应 ═══════

    private Map<String, Object> failResponse(List<String> errors, int totalRows) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "fail");
        resp.put("totalErrors", errors.size());
        resp.put("errors", errors);
        resp.put("totalRows", totalRows);
        return resp;
    }

    private Map<String, Object> confirmResponse(Prepared p, MatchResult m) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PendingItem item : m.pending) {
            List<Map<String, Object>> cands = new ArrayList<>();
            for (OpsValueAddedProduct c : item.candidates) {
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("productId", c.getId());
                cand.put("model", c.getModel());
                cand.put("salePrice", c.getSalePrice());
                cand.put("retailFloorPrice", c.getRetailFloorPrice());
                cand.put("groupLowestPrice", c.getGroupLowestPrice());
                cands.add(cand);
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("key", item.key);
            it.put("brand", item.brand);
            it.put("categoryName", item.catCn);
            it.put("modelKeyword", item.modelKeyword);
            it.put("rowNumbers", item.rowNums);
            it.put("candidates", cands);
            items.add(it);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "confirm");
        resp.put("fileMd5", p.fileMd5);
        resp.put("totalRows", p.totalRows);
        resp.put("pendingItems", items);
        resp.put("stats", statsMap(m));
        return resp;
    }

    private Map<String, Object> statsMap(MatchResult m) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("exactResolved", m.exact);
        stats.put("aliasResolved", m.alias);
        stats.put("specialSkipped", m.specialSkipped);
        stats.put("pendingRows", m.pendingRowCount());
        return stats;
    }

    // ═══════ 入库（收据校验 + 单事务原子写入） ═══════

    private ImportResult doInsert(Prepared p, Long corpId) throws Exception {
        // ── 收据号组内一致性校验 ──
        List<String> receiptErrors = new ArrayList<>();
        validateReceiptGroups(p.rows, receiptErrors);
        if (!receiptErrors.isEmpty()) {
            return ImportResult.fail("RECEIPT_CHECK", receiptErrors, p.totalRows);
        }
        // ── 收据号唯一性校验（文件内去重后查库，任一已存在则整文件拒绝）──
        List<String> uniqueReceiptNos = p.rows.stream()
                .map(r -> r.receiptNo)
                .filter(r -> r != null && !r.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (!uniqueReceiptNos.isEmpty()) {
            List<String> existing = mapper.findExistingReceiptNos(
                    uniqueReceiptNos, corpId != null ? corpId : 4L);
            if (!existing.isEmpty()) {
                return ImportResult.fail("RECEIPT_DUP",
                        List.of("以下收据号已存在，请检查：" + dupList(existing)), p.totalRows);
            }
        }
        return insertRows(p, corpId);
    }

    private String dupList(List<String> existing) {
        if (existing.size() <= 10) return String.join(", ", existing);
        return String.join(", ", existing.subList(0, 10)) + " ...共" + existing.size() + "个";
    }

    /** 单事务 JDBC batch 原子写入（DataSourceUtils 取 Spring 事务连接，全量或全不）。 */
    private ImportResult insertRows(Prepared p, Long corpId) throws Exception {
        String ts = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Map<String, String> batchNoMap = new HashMap<>();
        String insertSql = "INSERT INTO ops_business_data "
                + "(batch_no, company, customer, brand, category, model, "
                + "sale_price, agent, agent_phone, is_special, commission_rate, "
                + "sale_date, receipt_date, finance_receipt_date, fund_dynamic_rows, "
                + "receipt_no, retail_floor_price, group_lowest_price, "
                + "calc_status, remark, corp_id, operator_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        int batchCount = 0;
        PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(insertSql);
        try {
            for (ParsedRow pr : p.rows) {
                String bn = batchNoMap.computeIfAbsent(pr.company, c -> c + ts);
                bindRow(ps, pr, bn, corpId);
                ps.addBatch();
                batchCount++;
                if (batchCount % 500 == 0) ps.executeBatch();
            }
            if (batchCount % 500 != 0) ps.executeBatch();
            if (batchCount != p.rows.size()) {
                log.error("Row count mismatch: batch={} expected={}", batchCount, p.rows.size());
                throw new RuntimeException("入库行数(" + batchCount
                        + ")与有效行数(" + p.rows.size() + ")不一致");
            }
            log.info("Import done: {} rows, corpId={}", batchCount, corpId);
            return ImportResult.success(batchCount, p.totalRows);
        } finally {
            ps.close();
        }
    }

    /** 绑定 22 列参数：特批行提成比例落库 commission_rate；零售底价/集团最低价取自匹配产品 */
    private void bindRow(PreparedStatement ps, ParsedRow pr, String batchNo, Long corpId)
            throws SQLException {
        int idx = 1;
        ps.setString(idx++, batchNo);
        ps.setString(idx++, pr.company);
        ps.setString(idx++, pr.customer);
        ps.setString(idx++, pr.brand);
        ps.setString(idx++, pr.catKey);
        ps.setString(idx++, pr.model);
        ps.setBigDecimal(idx++, pr.salePrice);
        ps.setString(idx++, pr.agent);
        ps.setString(idx++, pr.agentPhone);
        ps.setString(idx++, pr.special ? "是" : "否");
        // 特批行的提成比例直接落库 commission_rate；非特批行留空，由提成计算按规则填充
        ps.setBigDecimal(idx++, pr.special ? pr.commissionRate : null);
        ps.setString(idx++, pr.saleDate);
        ps.setString(idx++, pr.receiptDate);
        ps.setString(idx++, pr.financeReceiptDate);
        ps.setObject(idx++, pr.fundDynamicRows);
        ps.setString(idx++, pr.receiptNo);
        ps.setBigDecimal(idx++, pr.product != null
                ? pr.product.getRetailFloorPrice() : null);
        ps.setBigDecimal(idx++, pr.product != null
                ? pr.product.getGroupLowestPrice() : null);
        ps.setString(idx++, "未计算");
        ps.setString(idx++, pr.dept.isEmpty() ? null : "部门: " + pr.dept);
        ps.setLong(idx++, corpId);
        ps.setLong(idx++, corpId);
    }

    // ═══════ Category dict ═══════

    private Set<String> loadValidCategories() {
        if (categoryNameCache != null) return categoryNameCache;
        synchronized (this) {
            if (categoryNameCache != null) return categoryNameCache;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/dict/data?typeCode=" + CATEGORY_TYPE_CODE, null);
                Object data = resp.get("data");
                if (data instanceof List) {
                    Set<String> set = new HashSet<>();
                    Map<String, String> nameToKey = new HashMap<>();
                    for (Object item : (List<?>) data) {
                        if (item instanceof Map) {
                            Object val = ((Map<?, ?>) item).get("itemValue");
                            Object key = ((Map<?, ?>) item).get("itemKey");
                            if (val != null) {
                                set.add(val.toString());
                                if (key != null) nameToKey.put(val.toString(), key.toString());
                            }
                        }
                    }
                    if (!set.isEmpty()) {
                        categoryNameCache = set;
                        categoryNameToKeyCache = nameToKey;
                        log.info("Loaded {} categories from dict", set.size());
                        return set;
                    }
                }
            } catch (Exception e) {
                log.warn("Dict load failed, using defaults: {}", e.getMessage());
            }
            categoryNameCache = DEFAULT_CATEGORIES;
            return categoryNameCache;
        }
    }

    /** Get dynamic category name→key mapping, loading from dict if needed. */
    private Map<String, String> getCategoryNameToKey() {
        loadValidCategories(); // ensure cache is populated
        if (categoryNameToKeyCache != null) return categoryNameToKeyCache;
        // Fallback when dict API is down: same as old hardcoded map
        return Map.ofEntries(
                Map.entry("燃气灶", "1"), Map.entry("抽油烟机", "2"),
                Map.entry("热水器", "3"), Map.entry("壁挂炉", "4"),
                Map.entry("壁挂炉以旧换新销售", "5"), Map.entry("户内美装销售", "6"),
                Map.entry("定尺波纹管", "7"), Map.entry("户内美装工程", "8"),
                Map.entry("户内美装运维", "9"), Map.entry("保险", "10"),
                Map.entry("美装管道整改", "11"), Map.entry("美装安全评估", "12")
        );
    }

    /** Resolve Chinese category name → dict key dynamically from platform dict. */
    private String mapCategoryKey(String catCn) {
        return getCategoryNameToKey().getOrDefault(catCn, catCn);
    }

    // ═══════ Header indices ═══════

    // header index slots（headerIndices 返回数组的下标）
    private static final int I_CUSTOMER = 0;
    private static final int I_CATEGORY = 1;
    private static final int I_BRAND = 2;
    private static final int I_MODEL = 3;
    private static final int I_PRICE = 4;
    private static final int I_COMPANY = 5;
    private static final int I_DEPT = 6;
    private static final int I_AGENT = 7;
    private static final int I_AGENT_PHONE = 8;
    private static final int I_SALE_DATE = 9;
    private static final int I_RECEIPT_DATE = 10;
    private static final int I_FINANCE_DATE = 11;
    private static final int I_FUND_ROWS = 12;
    private static final int I_RECEIPT_NO = 13;
    private static final int I_SPECIAL = 14;
    private static final int I_RATIO = 15;

    /** 表头校验 + 定位各列下标；任一必填列缺失 → 错误并返回 null。 */
    private int[] headerIndices(String[] headers, List<String> errors) {
        String[] requiredHeaders = {
            "客户", "品类", "品牌", "型号", "销售价", "公司", "经办人", "经办人手机号",
            "市场收款日期", "财务收款日期", "资金动态表横排数"
        };
        for (String h : requiredHeaders) {
            if (indexOf(headers, h) < 0) errors.add("缺少列: " + h);
        }
        if (!errors.isEmpty()) return null;
        int[] idx = new int[16];
        idx[I_CUSTOMER] = indexOf(headers, "客户");
        idx[I_CATEGORY] = indexOf(headers, "品类");
        idx[I_BRAND] = indexOf(headers, "品牌");
        idx[I_MODEL] = indexOf(headers, "型号");
        idx[I_PRICE] = indexOf(headers, "销售价");
        idx[I_COMPANY] = indexOf(headers, "公司");
        idx[I_DEPT] = indexOf(headers, "部门");
        idx[I_AGENT] = indexOf(headers, "经办人");
        idx[I_AGENT_PHONE] = indexOf(headers, "经办人手机号");
        idx[I_SALE_DATE] = indexOf(headers, "销售日期");
        idx[I_RECEIPT_DATE] = indexOf(headers, "市场收款日期");
        idx[I_FINANCE_DATE] = indexOf(headers, "财务收款日期");
        idx[I_FUND_ROWS] = indexOf(headers, "资金动态表横排数");
        idx[I_RECEIPT_NO] = indexOf(headers, "收据号");
        // 特批两列必须精确匹配：contains 匹配会先命中"是否特批"列
        idx[I_SPECIAL] = indexOfExact(headers, "是否特批");
        // 旧模板曾用"是否特比"（笔误）与"是否特批比例"，向下兼容存量文件
        if (idx[I_SPECIAL] < 0) idx[I_SPECIAL] = indexOfExact(headers, "是否特比");
        if (idx[I_SPECIAL] < 0) idx[I_SPECIAL] = indexOfExact(headers, "是否特批比例");
        idx[I_RATIO] = indexOfExact(headers, "提成比例");
        return idx;
    }

    // ═══════ Validation ═══════

    private void checkCategory(ParsedRow pr, int rowNum,
                                Set<String> validCategories, List<String> errors) {
        String cat = pr.catCn;
        if (cat.isEmpty()) {
            errors.add("第" + rowNum + "行: 品类为空");
        } else if (!validCategories.contains(cat)) {
            errors.add("第" + rowNum + "行: 品类'" + cat + "'不在字典范围内");
        }
    }

    /** Categories whose personal commission type is "阶梯" — brand/model are required. */
    private Set<String> loadTieredCommissionCategories(Long corpId) {
        try {
            List<OpsPersonalCommissionRule> rules = commissionRuleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpsPersonalCommissionRule>()
                            .eq(OpsPersonalCommissionRule::getCorpId, corpId)
                            .eq(OpsPersonalCommissionRule::getCommissionType, "阶梯")
                            .eq(OpsPersonalCommissionRule::getStatus, 1));
            if (rules.isEmpty()) return Set.of();
            Set<String> cats = new HashSet<>();
            for (OpsPersonalCommissionRule r : rules) {
                if (r.getCategory() != null && !r.getCategory().isBlank())
                    cats.add(r.getCategory());
            }
            log.info("Tiered commission categories for corpId={}: {}", corpId, cats);
            return cats;
        } catch (Exception e) {
            log.warn("Failed to load tiered commission categories for corpId={}, falling back to empty: {}",
                    corpId, e.getMessage());
            return Set.of();
        }
    }

    private void validateFields(ParsedRow pr, int rowNum, List<String> errors,
                                Set<String> tieredCategories) {
        if (pr.company.isEmpty())
            errors.add("第" + rowNum + "行: 公司为空");
        else if (!VALID_COMPANIES.contains(pr.company))
            errors.add("第" + rowNum + "行: 无效公司 \"" + pr.company + "\"");
        // Brand/model required only for tiered commission categories (by category key).
        // 特批行按提成比例直接计算提成、不参与规则匹配，品牌/型号不作此限制
        if (!pr.special && pr.catKey != null && tieredCategories.contains(pr.catKey)) {
            if (pr.brand.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，品牌不能为空");
            if (pr.model.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，型号不能为空");
        }
        validateSpecialRatio(pr, rowNum, errors);
        if (pr.agent == null || pr.agent.isBlank())
            errors.add("第" + rowNum + "行: 经办人为空");
        if (pr.salePrice == null)
            errors.add("第" + rowNum + "行: 销售价为空或格式不正确");
        else if (pr.salePrice.compareTo(java.math.BigDecimal.ZERO) < 0)
            errors.add("第" + rowNum + "行: 销售价不能为负数");
        String ap = pr.agentPhone;
        if (ap == null || ap.isEmpty())
            errors.add("第" + rowNum + "行: 经办人手机号为空");
        else if (!ap.matches("\\d{11}"))
            errors.add("第" + rowNum + "行: 经办人手机号格式不正确");
        // Date validation
        if (pr.saleDate == null)
            errors.add("第" + rowNum + "行: 销售日期格式不正确（支持: 2026-06-23 / 6月23日 / 2026年6月23日）");
        // 市场收款日期必填
        if (pr.receiptDateRaw == null || pr.receiptDateRaw.isEmpty())
            errors.add("第" + rowNum + "行: 市场收款日期为空");
        else if (pr.receiptDate == null)
            errors.add("第" + rowNum + "行: 市场收款日期格式不正确（支持: 2026-06-23 / 6月23日 / 2026年6月23日）");
        // 财务收款日期可选，但格式错误时拒绝该行（连带整文件拒绝）
        if (pr.financeReceiptDateRaw != null && !pr.financeReceiptDateRaw.isEmpty()
                && pr.financeReceiptDate == null)
            errors.add("第" + rowNum + "行: 财务收款日期格式不正确（支持: 2026-06-23 / 6月23日 / 2026年6月23日）");
        // 资金动态表横排数可选，但必须为数字
        if (pr.fundDynamicRowsRaw != null && !pr.fundDynamicRowsRaw.isEmpty()
                && pr.fundDynamicRows == null)
            errors.add("第" + rowNum + "行: 资金动态表横排数必须为数字");
    }

    /** 提成比例：特批=是 → 必填且大于 0；特批=否/空 → 可空（按默认"否"处理） */
    private void validateSpecialRatio(ParsedRow pr, int rowNum, List<String> errors) {
        if (!pr.special) return;
        if (pr.commissionRate == null) {
            errors.add("第" + rowNum + "行：特批数据提成比例不能为空");
        } else if (pr.commissionRate.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("第" + rowNum + "行：特批数据提成比例必须大于0");
        }
    }

    // ═══════ Receipt group validation ═══════

    /**
     * Validate only customer name consistency within the same receipt number.
     * Multiple products of different categories/brands/models/prices on one
     * receipt is normal business practice.
     */
    private void validateReceiptGroups(List<ParsedRow> rows, List<String> errors) {
        // Build index: receiptNo → list of (rowIndex)
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ParsedRow pr = rows.get(i);
            if (pr.receiptNo != null && !pr.receiptNo.isEmpty()) {
                groups.computeIfAbsent(pr.receiptNo, k -> new ArrayList<>()).add(i);
            }
        }

        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            String receiptNo = entry.getKey();
            List<Integer> indices = entry.getValue();
            if (indices.size() <= 1) continue;

            int baseIdx = indices.get(0);
            ParsedRow base = rows.get(baseIdx);
            int baseRowNum = baseIdx + 2; // header = row 1, data starts at row 2

            for (int j = 1; j < indices.size(); j++) {
                int compIdx = indices.get(j);
                ParsedRow comp = rows.get(compIdx);
                int compRowNum = compIdx + 2;

                if (!Objects.equals(base.customer, comp.customer)) {
                    errors.add("收据号" + receiptNo + "：第" + baseRowNum
                            + "行与第" + compRowNum + "行的客户名称不一致");
                }
            }
        }
    }

    // ═══════ Parsing ═══════

    private ParsedRow parseRow(String[] cols, int[] idx) {
        String brand = val(cols, idx[I_BRAND]);
        String model = val(cols, idx[I_MODEL]);
        String cat = val(cols, idx[I_CATEGORY]);
        String customer = val(cols, idx[I_CUSTOMER]);
        String company = val(cols, idx[I_COMPANY]);
        String agent = val(cols, idx[I_AGENT]);
        String agentPhone = val(cols, idx[I_AGENT_PHONE]);
        String priceStr = val(cols, idx[I_PRICE]);

        // Skip only TRULY blank rows: ALL meaningful fields empty.
        // Any row with partial data must go through validation —
        // if validation fails, the ENTIRE file is rejected (all-or-nothing).
        if (isBlankRow(cat, brand, model, customer, company,
                agent, agentPhone, priceStr)) {
            return null;
        }

        ParsedRow pr = new ParsedRow();
        pr.company = company;
        pr.customer = customer;
        pr.brand = brand;
        pr.model = model;
        pr.catCn = cat;
        pr.salePrice = toDecimal(priceStr);
        pr.agent = agent;
        pr.agentPhone = agentPhone;
        pr.dept = val(cols, idx[I_DEPT]);
        // Normalize dates: convert Chinese/ISO/Excel formats → YYYY-MM-DD
        pr.saleDate = normalizeDate(val(cols, idx[I_SALE_DATE]));
        String rawReceipt = val(cols, idx[I_RECEIPT_DATE]);
        pr.receiptDateRaw = rawReceipt;
        pr.receiptDate = normalizeDate(rawReceipt);
        String rawFinance = val(cols, idx[I_FINANCE_DATE]);
        pr.financeReceiptDateRaw = rawFinance;
        pr.financeReceiptDate = normalizeDate(rawFinance);
        String rawFundRows = val(cols, idx[I_FUND_ROWS]);
        pr.fundDynamicRowsRaw = rawFundRows;
        pr.fundDynamicRows = toInteger(rawFundRows);
        pr.receiptNo = val(cols, idx[I_RECEIPT_NO]);
        // 是否特批：仅精确"是"视为特批，空/其它一律按"否"
        String rawSpecial = val(cols, idx[I_SPECIAL]);
        pr.special = "是".equals(rawSpecial);
        pr.commissionRate = parseRatio(val(cols, idx[I_RATIO]));
        return pr;
    }

    private boolean isBlankRow(String cat, String brand, String model, String customer,
            String company, String agent, String agentPhone, String priceStr) {
        return cat.isEmpty() && brand.isEmpty() && model.isEmpty()
                && customer.isEmpty() && company.isEmpty()
                && agent.isEmpty() && agentPhone.isEmpty()
                && priceStr.isEmpty();
    }

    // ═══════ Helpers ═══════

    private String md5Of(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                + "wb = openpyxl.load_workbook('"
                + file.toAbsolutePath() + "')\n"
                + "required = ['客户','品类','品牌','型号','销售价','收据号']\n"
                + "# 多 sheet 模板（1.xlsx）：选前 10 行含必填列名最多的 sheet\n"
                + "best_ws = None; best_score = -1\n"
                + "for ws in wb.worksheets:\n"
                + "    text = '|'.join(str(c.value) if c.value is not None else '' for row in ws.iter_rows(min_row=1, max_row=10) for c in row)\n"
                + "    score = sum(1 for k in required if k in text)\n"
                + "    if score > best_score:\n"
                + "        best_ws = ws; best_score = score\n"
                + "ws = best_ws\n"
                + "# 定位表头行（含 3 个以上必填列名），非首行表头也能识别\n"
                + "header_row = 1\n"
                + "for r in range(1, 11):\n"
                + "    rowvals = [str(ws.cell(r, c).value) if ws.cell(r, c).value is not None else '' for c in range(1, ws.max_column + 1)]\n"
                + "    if sum(1 for k in required if k in '|'.join(rowvals)) >= 3:\n"
                + "        header_row = r\n"
                + "        break\n"
                + "# Build fill map from merged cell ranges (MergedCell is read-only)\n"
                + "fills = {}\n"
                + "for mg in ws.merged_cells.ranges:\n"
                + "    val = ws.cell(mg.min_row, mg.min_col).value\n"
                + "    for r in range(mg.min_row, mg.max_row + 1):\n"
                + "        for c in range(mg.min_col, mg.max_col + 1):\n"
                + "            fills[(r, c)] = val\n"
                + "for row_idx, row in enumerate(ws.iter_rows(min_row=header_row, values_only=True), header_row):\n"
                + "    vals = []\n"
                + "    for col_idx, v in enumerate(row, 1):\n"
                + "        if v is None and (row_idx, col_idx) in fills:\n"
                + "            v = fills[(row_idx, col_idx)]\n"
                + "        vals.append(str(v).replace('\\n',' ').replace('\\r',' ') if v is not None else '')\n"
                + "    print('\\t'.join(vals))\n");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
        p.waitFor();
        return out;
    }

    private int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++)
            if (arr[i].contains(key)) return i;
        return -1;
    }

    /** 精确表头匹配——contains 匹配会先命中"是否特批"导致"提成比例"取错列 */
    private int indexOfExact(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++)
            if (arr[i] != null && arr[i].trim().equals(key)) return i;
        return -1;
    }

    /** 解析提成比例：去除末尾 % 后转数字；空/非法 → null（配合特批必填校验） */
    private BigDecimal parseRatio(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        return toDecimal(t);
    }

    private String val(String[] arr, int idx) {
        return idx >= 0 && idx < arr.length ? arr[idx].trim() : "";
    }

    private BigDecimal toDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /** 解析整数（资金动态表横排数）。空 → null（可选）；非整数 → null（配合 raw 判错）。 */
    private Integer toInteger(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim();
        // Excel 数字单元格可能读作 "12.0"
        if (t.matches("\\d+\\.0+")) t = t.substring(0, t.indexOf('.'));
        try { return Integer.parseInt(t); } catch (Exception e) { return null; }
    }

    /**
     * Normalize a date string from Excel to YYYY-MM-DD format.
     * Returns null if the input is empty or cannot be parsed.
     * <p>
     * Supported formats:
     * <ul>
     *   <li>ISO: 2026-06-23 / 2026/06/23 / 2026.06.23</li>
     *   <li>Chinese: 2026年6月23日 / 6月23日</li>
     *   <li>Excel serial number (days since 1899-12-30)</li>
     *   <li>openpyxl datetime: 2026-06-23 00:00:00</li>
     * </ul>
     * For formats without a year (e.g. 6月23日), the current year is used.
     */
    private String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();

        // 1. Excel serial number (days since 1899-12-30)
        try {
            double serial = Double.parseDouble(raw);
            if (serial > 1 && serial < 100000) {
                long epochDay = (long) serial - 2; // 1900-01-01 = serial 1 for Excel
                return java.time.LocalDate.of(1900, 1, 1)
                        .plusDays(epochDay)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        } catch (NumberFormatException ignored) {}

        // 2. openpyxl datetime: "2026-06-23 00:00:00"
        if (raw.contains(" ") && raw.length() >= 10) {
            String datePart = raw.substring(0, 10);
            if (datePart.matches("\\d{4}-\\d{2}-\\d{2}")) return datePart;
        }

        // 3. ISO: 2026-06-23 / 2026/06/23 / 2026.06.23
        if (raw.matches("\\d{4}[-/.](0[1-9]|1[0-2])[-/.](0[1-9]|[12]\\d|3[01])")) {
            return raw.replace('/', '-').replace('.', '-');
        }

        // 4. Chinese with year: "2026年6月23日" or "2026年06月23日"
        java.util.regex.Matcher ym = java.util.regex.Pattern.compile(
                "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").matcher(raw);
        if (ym.matches()) {
            return String.format("%s-%02d-%02d",
                    ym.group(1),
                    Integer.parseInt(ym.group(2)),
                    Integer.parseInt(ym.group(3)));
        }

        // 5. Chinese without year: "6月23日" or "06月23日"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日$").matcher(raw);
        if (m.matches()) {
            int year = java.time.LocalDate.now().getYear();
            return String.format("%d-%02d-%02d",
                    year,
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
        }

        return null; // unparseable
    }
}
