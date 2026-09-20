package com.aip.ops.service.impl;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.imports.BusinessDataImportHandler;
import com.aip.ops.imports.ImportResult;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.client.PlatformInternalClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Objects;
import java.util.*;

@Slf4j
@Service
public class BusinessDataServiceImpl implements BusinessDataService {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsPersonalCommissionRuleMapper ruleMapper;
    private final DataScopeService dataScopeService;
    private final DataSource dataSource;
    private final PlatformInternalClient platformInternalClient;
    private final BusinessDataImportHandler importHandler;
    private final ImportTaskService importTaskService;

    /** 锁定状态：已上报集团的数据禁止删除/修改/重新计算（所有组织一致） */
    public static final String REPORTED_STATUS = "已上报集团";

    /** 汇总 Excel 的公司列固定展示顺序（未在列表中的公司按名称排序追加） */
    private static final List<String> COMPANY_ORDER =
            List.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

    /** 汇总 Excel 的品类行固定展示顺序（字典 itemKey）：户内美装三品类（销售/工程/运维）
     *  6,8,9 连排相邻，定尺波纹管 7 后移至三元组之后，其余按 key 升序。
     *  未在映射中的品类追加在末尾。 */
    private static final List<String> CATEGORY_ORDER =
            List.of("1", "2", "3", "4", "5", "6", "8", "9", "7", "10", "11", "12");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Dynamic category maps loaded from platform dict at first access. */
    private static volatile Map<String, String> CN_TO_KEY = null;
    public static volatile Map<String, String> KEY_TO_CN = null;

    static {
        // Init with hardcoded fallback maps (used when dict API is unreachable)
        Map<String, String> cn2key = new HashMap<>();
        cn2key.put("燃气灶", "1"); cn2key.put("抽油烟机", "2");
        cn2key.put("热水器", "3"); cn2key.put("壁挂炉", "4");
        cn2key.put("壁挂炉以旧换新销售", "5"); cn2key.put("户内美装销售", "6");
        cn2key.put("定尺波纹管", "7"); cn2key.put("户内美装工程", "8");
        cn2key.put("户内美装运维", "9"); cn2key.put("保险", "10");
        cn2key.put("美装管道整改", "11"); cn2key.put("美装安全评估", "12");
        cn2key.put("气密性检测", "qimi");
        CN_TO_KEY = Collections.unmodifiableMap(cn2key);
        Map<String, String> key2cn = new HashMap<>();
        cn2key.forEach((cn, k) -> key2cn.put(k, cn));
        KEY_TO_CN = Collections.unmodifiableMap(key2cn);
    }

    /** Category name cache from platform dict (volatile for DCL) */
    private volatile Set<String> categoryNameCache;
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "燃气灶", "抽油烟机", "热水器", "壁挂炉",
            "壁挂炉以旧换新销售", "户内美装销售", "定尺波纹管",
            "户内美装工程", "户内美装运维", "保险",
            "美装管道整改", "美装安全评估", "气密性检测"
    );

    public BusinessDataServiceImpl(
            OpsBusinessDataMapper mapper,
            OpsValueAddedProductMapper productMapper,
            OpsPersonalCommissionRuleMapper ruleMapper,
            DataScopeService dataScopeService,
            DataSource dataSource,
            PlatformInternalClient platformInternalClient,
            BusinessDataImportHandler importHandler,
            ImportTaskService importTaskService) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.ruleMapper = ruleMapper;
        this.dataScopeService = dataScopeService;
        this.dataSource = dataSource;
        this.platformInternalClient = platformInternalClient;
        this.importHandler = importHandler;
        this.importTaskService = importTaskService;
    }
    public static String getCategoryDisplayName(String key) {
        if (key == null || key.isEmpty()) return "";
        return KEY_TO_CN.getOrDefault(key, key);
    }

    /**
     * 导出排序：收据号相同的行相邻（空/无收据号沉底），同收据号内按 id 升序，
     * 保证「收款金额首行显示」的首行 = 组内最早录入行。
     */
    public static void sortByReceiptNo(List<OpsBusinessData> rows) {
        rows.sort(Comparator
                .comparing((OpsBusinessData d) -> d.getReceiptNo() == null || d.getReceiptNo().isEmpty())
                .thenComparing(d -> d.getReceiptNo() == null ? "" : d.getReceiptNo())
                .thenComparing(OpsBusinessData::getId));
    }

    /**
     * 导出兜底：存量行型号只写关键字（如武山子公司）导入时无法精确匹配产品，
     * 价格为空。此处按「品牌+品类相同 + 产品型号含关键字」唯一命中产品后，
     * 内存填充零售底价/集团最低价——仅影响导出文件，不写库。
     */
    public static void fillExportPrices(List<OpsBusinessData> rows, List<OpsValueAddedProduct> products) {
        if (rows == null || rows.isEmpty() || products == null || products.isEmpty()) return;
        for (OpsBusinessData d : rows) {
            if (d.getRetailFloorPrice() != null && d.getGroupLowestPrice() != null) continue;
            OpsValueAddedProduct p = matchProductExactOrKeyword(d.getBrand(), d.getCategory(), d.getModel(), products);
            if (p == null) continue;
            if (d.getRetailFloorPrice() == null) d.setRetailFloorPrice(p.getRetailFloorPrice());
            if (d.getGroupLowestPrice() == null) d.setGroupLowestPrice(p.getGroupLowestPrice());
        }
    }

    /**
     * 品牌+品类必须一致；型号先做归一化精确匹配（去空格/转小写），
     * 否则退化关键字包含匹配。多个包含候选视为歧义 → 返回 null 不填充，避免错价。
     */
    public static OpsValueAddedProduct matchProductExactOrKeyword(
            String brand, String category, String model, List<OpsValueAddedProduct> products) {
        if (brand == null || brand.isBlank() || category == null || category.isBlank()
                || model == null || model.isBlank() || products == null) return null;
        String normModel = normalizeModel(model);
        if (normModel.isEmpty()) return null;
        List<OpsValueAddedProduct> containsMatches = new ArrayList<>();
        for (OpsValueAddedProduct p : products) {
            if (p == null || !brand.equals(p.getBrand()) || !category.equals(p.getCategory())) continue;
            if (p.getModel() == null || p.getModel().isBlank()) continue;
            String normPm = normalizeModel(p.getModel());
            if (normPm.equals(normModel)) return p;
            if (normPm.contains(normModel)) containsMatches.add(p);
        }
        return containsMatches.size() == 1 ? containsMatches.get(0) : null;
    }

    /** 型号归一化：去全部空白（含全角空格）并转小写，用于关键字包含匹配 */
    private static String normalizeModel(String s) {
        return s.replaceAll("[\\s\\u3000]+", "").toLowerCase();
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo, String category, String isSpecial) {
        // Build visible corp IDs once
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> corpIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        // ── COUNT + STATS: single-row aggregate queries (no full table scan) ──
        long total = mapper.countFiltered(company, batchNo, agent, receiptNo, category, isSpecial, corpIdList);
        java.util.Map<String, java.math.BigDecimal> stats =
                mapper.aggregateStats(company, batchNo, agent, receiptNo, category, isSpecial, corpIdList);
        BigDecimal totalSale = stats != null && stats.get("totalSale") != null
                ? stats.get("totalSale") : BigDecimal.ZERO;
        BigDecimal totalCommission = stats != null && stats.get("totalCommission") != null
                ? stats.get("totalCommission") : BigDecimal.ZERO;

        // Per-category totals (same dedup rule as overall stats)
        List<Map<String, Object>> categoryStats =
                mapper.categoryStats(company, batchNo, agent, receiptNo, category, isSpecial, corpIdList);
        normalizeCategoryStats(categoryStats);

        // ── DATA: only fetch the requested page ──
        int offset = (pageNum - 1) * pageSize;
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
        if (receiptNo != null && !receiptNo.isEmpty()) qw.eq(OpsBusinessData::getReceiptNo, receiptNo);
        if (category != null && !category.isEmpty()) qw.eq(OpsBusinessData::getCategory, category);
        if (isSpecial != null && !isSpecial.isEmpty()) qw.eq(OpsBusinessData::getIsSpecial, isSpecial);
        if (corpIdList != null) qw.in(OpsBusinessData::getCorpId, corpIdList);
        qw.orderByDesc(OpsBusinessData::getId);
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsBusinessData> records = mapper.selectList(qw);

        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum,
                "totalSale", totalSale.setScale(2, RoundingMode.HALF_UP),
                "totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP),
                "categoryStats", categoryStats);
    }

    /** Round per-category totals to 2 decimals for display consistency. */
    private static void normalizeCategoryStats(List<Map<String, Object>> categoryStats) {
        for (Map<String, Object> row : categoryStats) {
            if (row.get("totalSale") instanceof BigDecimal b) row.put("totalSale", b.setScale(2, RoundingMode.HALF_UP));
            if (row.get("totalCommission") instanceof BigDecimal b) row.put("totalCommission", b.setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Override
    public List<String> getBatches() {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getBatchNo)
                .groupBy(OpsBusinessData::getBatchNo);
        applyCorpScope(qw);
        qw.orderByDesc(OpsBusinessData::getBatchNo);
        List<OpsBusinessData> all = mapper.selectList(qw);
        Set<String> set = new LinkedHashSet<>();
        for (OpsBusinessData d : all) if (d.getBatchNo() != null) set.add(d.getBatchNo());
        return new ArrayList<>(set);
    }

    @Override
    @Transactional
    public Map<String, Object> importExcel(MultipartFile file, String batchNo, Long corpId) {
        Path tmpFile = null;
        try {
            // Save uploaded file to temp path
            tmpFile = Files.createTempFile("import_", ".xlsx");
            file.transferTo(tmpFile);

            // Create task record and delegate to unified import handler
            String originalName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "import.xlsx";
            SysImportTask task = importTaskService.createTask("business_data",
                    originalName, tmpFile.toAbsolutePath().toString(), file.getSize());
            ImportResult result = importHandler.execute(task);

            // Map ImportResult → controller response format
            Map<String, Object> response = new LinkedHashMap<>();
            if ("success".equals(result.getStatus())) {
                response.put("status", "success");
                response.put("count", result.getCount());
            } else {
                response.put("status", "fail");
                response.put("totalErrors", result.getFailRows());
                response.put("errors", result.getErrors());
            }
            return response;
        } catch (Exception e) {
            log.error("导入Excel失败", e);
            return Map.of("status", "fail", "totalErrors", 1,
                    "errors", List.of("导入失败: " + e.getMessage()));
        } finally {
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
        }
    }

    /** Load valid category names + name↔key maps from platform dict. Falls back to defaults on failure. */
    private Set<String> loadValidCategories() {
        if (categoryNameCache != null) return categoryNameCache;
        synchronized (this) {
            if (categoryNameCache != null) return categoryNameCache;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
                Object data = resp.get("data");
                if (data instanceof List) {
                    Set<String> set = new HashSet<>();
                    Map<String, String> cn2key = new HashMap<>();
                    Map<String, String> key2cn = new HashMap<>();
                    for (Object item : (List<?>) data) {
                        if (item instanceof Map) {
                            Object val = ((Map<?, ?>) item).get("itemValue");
                            Object key = ((Map<?, ?>) item).get("itemKey");
                            if (val != null) {
                                String cn = val.toString();
                                set.add(cn);
                                if (key != null) {
                                    String k = key.toString();
                                    cn2key.put(cn, k);
                                    key2cn.put(k, cn);
                                }
                            }
                        }
                    }
                    if (!set.isEmpty()) {
                        categoryNameCache = set;
                        CN_TO_KEY = Collections.unmodifiableMap(cn2key);
                        KEY_TO_CN = Collections.unmodifiableMap(key2cn);
                        log.info("Loaded {} categories from platform dict", set.size());
                        return set;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load PRODUCT_CATEGORY dict, using defaults: {}", e.getMessage());
            }
            categoryNameCache = DEFAULT_CATEGORIES;
            return categoryNameCache;
        }
    }

    @Override
    public List<String> getUncalcBatches() {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getBatchNo)
                .eq(OpsBusinessData::getCalcStatus, "未计算")
                .groupBy(OpsBusinessData::getBatchNo)
                .orderByDesc(OpsBusinessData::getBatchNo);
        applyCorpScope(qw);
        List<OpsBusinessData> all = mapper.selectList(qw);
        return all.stream().map(OpsBusinessData::getBatchNo).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional
    public Map<String, Object> calcCommission(String batchNo) {
        // 锁定守卫：已上报集团的批次禁止重新计算（否则 CASE-WHEN UPDATE 会覆盖锁定状态）
        if (batchNo != null && !batchNo.isBlank()) {
            long reported = mapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                    .eq(OpsBusinessData::getBatchNo, batchNo)
                    .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS));
            if (reported > 0) {
                throw BusinessException.of("该批次包含" + reported + "条已上报集团数据，禁止重新计算");
            }
        }
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();

        // ── Build parent chain BEFORE querying rules ──
        // We need the full ancestor set so the rule query can fetch parent/ancestor
        // rules alongside the currently-visible corps' rules. Without this,
        // cascadeParentRules() would find parentMap entries but no parent rule data
        // in ruleMapByCorp, silently yielding zero commission for city partners.
        Map<Long, Long> parentMap = new HashMap<>();
        Set<Long> ruleCorpIds = new HashSet<>();
        if (visibleIds != null && !visibleIds.isEmpty()) {
            parentMap = buildParentChainMap(visibleIds);
            ruleCorpIds.addAll(visibleIds);
            // Walk the parent chain to collect ALL ancestor corp IDs
            for (Long child : visibleIds) {
                Long p = parentMap.get(child);
                while (p != null && p != 0L) {
                    ruleCorpIds.add(p);
                    // resolve next ancestor if known; stop at root
                    Long next = parentMap.get(p);
                    if (next == null && p != 0L) {
                        // parent-of-parent not in map yet — resolve it now
                        Long gp = resolveParentId(p);
                        if (gp != null) parentMap.put(p, gp);
                        next = gp;
                    }
                    p = next;
                }
            }
        }
        // Always include platform-level (corpId=0) as fallback
        ruleCorpIds.add(0L);

        // Load active rules filtered by the expanded corp ID set (visible + ancestors + platform)
        LambdaQueryWrapper<OpsPersonalCommissionRule> ruleQw =
                new LambdaQueryWrapper<OpsPersonalCommissionRule>()
                        .eq(OpsPersonalCommissionRule::getStatus, 1);
        if (visibleIds != null) {
            ruleQw.in(OpsPersonalCommissionRule::getCorpId, ruleCorpIds);
        }
        // (super-admin: visibleIds=null → no corp filter → all rules)
        List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(ruleQw);
        if (rules.isEmpty()) {
            log.warn("No active personal commission rules found for corp scope. "
                    + "All commission will be zero.");
        }
        // Group rules by (corpId, category) for per-tenant matching
        Map<Long, Map<String, OpsPersonalCommissionRule>> ruleMapByCorp = new HashMap<>();
        for (OpsPersonalCommissionRule r : rules) {
            Long cid = r.getCorpId() != null ? r.getCorpId() : 0L;
            ruleMapByCorp.computeIfAbsent(cid, k -> new HashMap<>())
                    .putIfAbsent(r.getCategory(), r);
        }

        // ── Cascade parent rules to child corps (org hierarchy inheritance) ──
        // Child corps inherit parent operator's rules; child's own rules take priority.
        if (visibleIds != null && !visibleIds.isEmpty()) {
            cascadeParentRules(ruleMapByCorp, parentMap);
        }

        // For super admin (visibleIds=null): merge all corp rules into a
        // platform-level fallback (corpId=0) so records from any org can
        // match when their own corp has no rules configured.
        if (visibleIds == null) {
            Map<String, OpsPersonalCommissionRule> allRules = new HashMap<>();
            for (Map<String, OpsPersonalCommissionRule> corpRules : ruleMapByCorp.values()) {
                for (Map.Entry<String, OpsPersonalCommissionRule> e : corpRules.entrySet()) {
                    allRules.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            ruleMapByCorp.put(0L, allRules);
            log.info("Super admin: merged {} rules from all corps as fallback", allRules.size());
        }

        LambdaQueryWrapper<OpsBusinessData> uncalcQw = new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getCalcStatus, "未计算");
        if (batchNo != null && !batchNo.isBlank()) {
            uncalcQw.eq(OpsBusinessData::getBatchNo, batchNo);
        }
        applyCorpScope(uncalcQw);
        List<OpsBusinessData> uncalcs = mapper.selectList(uncalcQw);

        if (uncalcs.isEmpty()) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }

        // ── Phase 1: Compute per-row commission (in-memory, per-corp rule matching) ──
        Map<Long, BigDecimal[]> results = new LinkedHashMap<>();
        for (OpsBusinessData d : uncalcs) {
            if (isSpecial(d)) {
                // 特批行：提成 = 销售价 × 提成比例 / 100，跳过个人提成规则匹配
                BigDecimal rate = d.getCommissionRate() != null
                        ? d.getCommissionRate() : BigDecimal.ZERO;
                BigDecimal amt = d.getSalePrice() == null ? BigDecimal.ZERO
                        : d.getSalePrice().multiply(rate)
                                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                results.put(d.getId(), new BigDecimal[]{rate, amt});
                continue;
            }
            Long dCorpId = d.getCorpId() != null ? d.getCorpId() : 0L;
            Map<String, OpsPersonalCommissionRule> corpRuleMap = ruleMapByCorp.getOrDefault(dCorpId,
                    ruleMapByCorp.getOrDefault(0L, Collections.emptyMap()));
            OpsPersonalCommissionRule rule = corpRuleMap.get(d.getCategory());
            BigDecimal[] cr = calcCommissionAmount(d.getSalePrice(),
                    d.getRetailFloorPrice(), d.getGroupLowestPrice(), rule);
            results.put(d.getId(), new BigDecimal[]{cr[0], cr[1]});
        }

        // ── Phase 2: Split by receipt×customer×category×model group ──
        int splitGroups = splitCommissionByReceiptGroup(uncalcs, results);

        // Count commissioned / zero AFTER splitting (results may have changed)
        int commissioned = 0, zero = 0;
        for (BigDecimal[] cr : results.values()) {
            if (cr[1].compareTo(BigDecimal.ZERO) > 0) commissioned++; else zero++;
        }

        // ── Phase 3: Batch SQL update (preserves existing CASE WHEN pattern) ──
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            int batchSize = 500;
            for (int i = 0; i < uncalcs.size(); i += batchSize) {
                int end = Math.min(i + batchSize, uncalcs.size());
                StringBuilder rateSql = new StringBuilder(
                        "UPDATE ops_business_data SET commission_rate = CASE ");
                StringBuilder amtSql  = new StringBuilder(
                        "UPDATE ops_business_data SET commission_amount = CASE ");
                StringBuilder ids = new StringBuilder();
                for (int j = i; j < end; j++) {
                    Long id = uncalcs.get(j).getId();
                    BigDecimal[] cr = results.get(id);
                    rateSql.append("WHEN id=").append(id)
                            .append(" THEN ").append(cr[0]).append(" ");
                    amtSql.append("WHEN id=").append(id)
                            .append(" THEN ").append(cr[1]).append(" ");
                    ids.append(id).append(",");
                }
                ids.setLength(ids.length() - 1);
                rateSql.append("END, calc_status='已计算' WHERE id IN (")
                        .append(ids).append(")");
                amtSql.append("END WHERE id IN (").append(ids).append(")");
                stmt.addBatch(rateSql.toString());
                stmt.addBatch(amtSql.toString());
                stmt.executeBatch();
            }
        } catch (Exception e) {
            log.error("批量计算提成失败", e);
            throw new RuntimeException("批量计算提成失败", e);
        }

        // ── Phase 4: Verify receipt group consistency ──
        verifyReceiptGroupConsistency(uncalcs, results);

        log.info("提成计算完成: {}条有提成, {}条0提成, {}个分组均分, 共{}条",
                commissioned, zero, splitGroups, uncalcs.size());
        return Map.of("total", uncalcs.size(), "commissioned", commissioned,
                "zero", zero, "splitGroups", splitGroups);
    }

    /**
     * Split commission evenly within receipt×customer×category×model groups (N > 1).
     * Groups are formed by (receiptNo, customer, category, model) so that:
     * <ul>
     *   <li>Same receipt + same customer + same category + same model
     *       + different agents → split evenly (multi-agent collaboration)</li>
     *   <li>Different category or different model → independent calculation,
     *       each row keeps its own commission</li>
     * </ul>
     * Rule inheritance across org hierarchy is handled by
     * {@link #cascadeParentRules} at rule-load time, not by grouping.
     * The last row in each group absorbs rounding remainder so the
     * sum of adjusted amounts matches the original total exactly.
     *
     * @return number of receipt×customer×category×model groups that were split
     */
    private int splitCommissionByReceiptGroup(List<OpsBusinessData> records,
                                               Map<Long, BigDecimal[]> results) {
        // Build receipt×customer×category×model groups
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : records) {
            String rn = d.getReceiptNo();
            if (rn != null && !rn.isEmpty()) {
                String key = rn + "|" + Objects.toString(d.getCustomer(), "")
                        + "|" + Objects.toString(d.getCategory(), "")
                        + "|" + Objects.toString(d.getModel(), "");
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }

        int splitGroups = 0;
        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            List<OpsBusinessData> group = entry.getValue();
            // 特批行不参与均分（提成按提成比例独立计算），仅对组内非特批行均分
            List<OpsBusinessData> splittable = group.stream()
                    .filter(d -> !isSpecial(d)).toList();
            if (splittable.size() <= 1) continue;
            splitGroups++;

            // Within a (receiptNo, customer, category, model) group, all rows
            // represent the same product sold to the same customer, so every row
            // should match the same commission rule and yield the same amount.
            // We pick the first row's result as the reference for splitting.
            BigDecimal[] firstResult = results.get(splittable.get(0).getId());
            BigDecimal totalAmount = (firstResult != null && firstResult[1] != null)
                    ? firstResult[1] : BigDecimal.ZERO;
            BigDecimal originalRate = (firstResult != null && firstResult[0] != null)
                    ? firstResult[0] : BigDecimal.ZERO;

            int n = splittable.size();
            // Rate per row = rule rate ÷ N (spec §2.4)
            BigDecimal perRowRate = originalRate.divide(
                    BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            // Amount per row = totalAmount ÷ N, last row absorbs rounding
            BigDecimal baseAmount = totalAmount.divide(
                    BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            BigDecimal used = baseAmount.multiply(BigDecimal.valueOf(n - 1));
            BigDecimal remainder = totalAmount.subtract(used);

            log.info("Receipt group {}: N={}, totalAmount={}, baseAmount={}, "
                    + "remainder={}, originalRate={}, perRowRate={}",
                    entry.getKey(), n, totalAmount, baseAmount, remainder,
                    originalRate, perRowRate);

            for (int i = 0; i < n; i++) {
                OpsBusinessData d = splittable.get(i);
                BigDecimal finalAmount = (i < n - 1) ? baseAmount : remainder;
                results.put(d.getId(), new BigDecimal[]{perRowRate, finalAmount});
            }
        }
        return splitGroups;
    }

    /**
     * 判定同一订单拆分多行的「收款金额」显示：
     * 收据号+客户相同、且经办人不唯一（多经办人协作）即视为同一订单拆分
     * ——不要求品类/型号相同，仅导出顺序中第一行显示收款金额，其余行留空。
     *
     * @return 收款金额需留空的行 id 集合
     */
    public static Set<Long> computeSplitOrderNonFirstIds(List<OpsBusinessData> rows) {
        Map<String, List<Long>> idGroups = new LinkedHashMap<>();
        Map<String, Set<String>> agentSets = new HashMap<>();
        for (OpsBusinessData d : rows) {
            String rn = d.getReceiptNo();
            if (rn == null || rn.isEmpty()) continue;
            String key = rn + "|" + Objects.toString(d.getCustomer(), "");
            idGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(d.getId());
            agentSets.computeIfAbsent(key, k -> new HashSet<>()).add(Objects.toString(d.getAgent(), ""));
        }
        Set<Long> blankIds = new HashSet<>();
        for (Map.Entry<String, List<Long>> e : idGroups.entrySet()) {
            List<Long> ids = e.getValue();
            if (ids.size() > 1 && agentSets.get(e.getKey()).size() > 1) {
                blankIds.addAll(ids.subList(1, ids.size()));
            }
        }
        return blankIds;
    }

    /**
     * Verify that no receipt group row has a negative or null amount
     * after splitting. Throws on data inconsistency to trigger rollback.
     */
    private void verifyReceiptGroupConsistency(List<OpsBusinessData> records,
                                                Map<Long, BigDecimal[]> results) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : records) {
            String rn = d.getReceiptNo();
            if (rn != null && !rn.isEmpty()) {
                String key = rn + "|" + Objects.toString(d.getCustomer(), "")
                        + "|" + Objects.toString(d.getCategory(), "")
                        + "|" + Objects.toString(d.getModel(), "");
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }

        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            List<OpsBusinessData> group = entry.getValue();
            if (group.size() <= 1) continue;

            BigDecimal groupSum = BigDecimal.ZERO;
            for (OpsBusinessData d : group) {
                BigDecimal[] cr = results.get(d.getId());
                if (cr == null || cr[1] == null || cr[1].compareTo(BigDecimal.ZERO) < 0) {
                    String msg = "Receipt group " + entry.getKey()
                            + " row id=" + d.getId()
                            + " has invalid amount after split: "
                            + (cr != null ? cr[1] : null);
                    log.error(msg);
                    throw new RuntimeException("提成均分校验失败：" + msg);
                }
                groupSum = groupSum.add(cr[1]);
            }
            log.debug("Receipt group {}: split total = {}", entry.getKey(), groupSum);
        }
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 是否特比行：特批行提成直接按提成比例计算，不参与规则匹配与均分。 */
    private boolean isSpecial(OpsBusinessData d) {
        return "是".equals(d.getIsSpecial());
    }

    private BigDecimal[] calcCommissionAmount(BigDecimal salePrice, BigDecimal floorPrice,
                                               BigDecimal groupPrice, OpsPersonalCommissionRule rule) {
        BigDecimal zero = BigDecimal.ZERO;
        if (salePrice == null || rule == null) return new BigDecimal[]{zero, zero};

        BigDecimal rate = rule.getCommissionRate() != null ? rule.getCommissionRate() : zero;
        if ("阶梯".equals(rule.getCommissionType())) {
            if (floorPrice == null || groupPrice == null) {
                BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{rate, amt};
            }
            if (salePrice.compareTo(floorPrice) >= 0) {
                BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{rate, amt};
            }
            if (salePrice.compareTo(groupPrice) >= 0) {
                BigDecimal midRate = new BigDecimal("5.00");
                BigDecimal amt = salePrice.multiply(midRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{midRate, amt};
            }
            return new BigDecimal[]{zero, zero};
        }
        // 固定金额：提成直接取规则的 commission_amount，不按销售额计算（rate 记 0）
        if ("固定金额".equals(rule.getCommissionType())) {
            BigDecimal fixed = rule.getCommissionAmount() != null ? rule.getCommissionAmount() : zero;
            return new BigDecimal[]{zero, fixed};
        }
        BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return new BigDecimal[]{rate, amt};
    }

    @Override
    public void exportExcel(String company, String batchNo, String agent, HttpServletResponse response) {
        try {
            LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
            if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
            if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
            if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
            applyCorpScope(qw);
            List<OpsBusinessData> all = mapper.selectList(qw);
            sortByReceiptNo(all);
            Set<Long> blankAmountIds = computeSplitOrderNonFirstIds(all);
            // 型号关键字行（如武山）兜底填充 零售底价/集团最低价（仅导出文件）
            fillExportPrices(all, productMapper.selectList(null));

            Path tsvFile = Files.createTempFile("export_", ".tsv");
            Path xlsFile = Path.of(tsvFile.toString().replace(".tsv", ".xlsx"));
            // Write header + rows as tab-separated
            StringBuilder sb = new StringBuilder();
            sb.append("批次\t公司\t客户\t品牌\t品类\t型号\t收款金额\t销售价\t零售底价\t集团最低价\t提成比例%\t提成金额\t状态\t经办人\t经办人手机号\t是否特比\t销售日期\t市场收款日期\t财务收款日期\t资金动态表横排数\t收据号\n");
            for (OpsBusinessData d : all) {
                sb.append(fmt(d.getBatchNo())).append('\t')
                  .append(fmt(d.getCompany())).append('\t').append(fmt(d.getCustomer())).append('\t')
                  .append(fmt(d.getBrand())).append('\t').append(getCategoryDisplayName(d.getCategory())).append('\t')
                  .append(fmt(d.getModel())).append('\t')
                  .append(blankAmountIds.contains(d.getId()) ? "" : fmt(d.getSalePrice())).append('\t')
                  .append(fmt(d.getSalePrice())).append('\t')
                  .append(fmt(d.getRetailFloorPrice())).append('\t').append(fmt(d.getGroupLowestPrice())).append('\t')
                  .append(fmt(d.getCommissionRate())).append('\t').append(fmt(d.getCommissionAmount())).append('\t')
                  .append(fmt(d.getCalcStatus())).append('\t').append(fmt(d.getAgent())).append('\t')
                  .append(fmt(d.getAgentPhone())).append('\t')
                  .append(fmt(d.getIsSpecial())).append('\t')
                  .append(fmt(d.getSaleDate())).append('\t').append(fmt(d.getReceiptDate())).append('\t')
                  .append(fmt(d.getFinanceReceiptDate())).append('\t').append(fmt(d.getFundDynamicRows())).append('\t')
                  .append(fmt(d.getReceiptNo())).append('\n');
            }
            Files.writeString(tsvFile, sb.toString());

            // Convert TSV → XLSX using Python
            new ProcessBuilder("python3", "-c",
                "import openpyxl,sys\n"
              + "wb=openpyxl.Workbook()\n"
              + "ws=wb.active\n"
              + "lines=open('" + tsvFile.toAbsolutePath() + "').read().strip().split('\\n')\n"
              + "for line in lines:\n  ws.append(line.split('\\t'))\n"
              + "wb.save('" + xlsFile.toAbsolutePath() + "')\n"
            ).redirectErrorStream(true).start().waitFor();

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"=?UTF-8?B?"
                    + Base64.getEncoder().encodeToString("业务数据导出.xlsx".getBytes(StandardCharsets.UTF_8))
                    + "?=\"; filename*=UTF-8''" + java.net.URLEncoder.encode("业务数据导出.xlsx", "UTF-8"));
            Files.copy(xlsFile, response.getOutputStream());
            response.getOutputStream().flush();
            Files.deleteIfExists(tsvFile); Files.deleteIfExists(xlsFile);
        } catch (Exception e) { log.error("导出失败", e); }
    }

    private String fmt(Object o) { return o == null ? "" : String.valueOf(o); }

    // ── 提成上报集团 & 品类提成汇总 ──

    @Override
    public int reportToGroup(String company, String batchNo, String agent, String receiptNo, String category) {
        // 显式 delete_flag=0：MyBatis-Plus 的 update 不会自动过滤逻辑删除
        LambdaUpdateWrapper<OpsBusinessData> uw = new LambdaUpdateWrapper<>();
        uw.set(OpsBusinessData::getCalcStatus, REPORTED_STATUS)
          .eq(OpsBusinessData::getDeleteFlag, 0)
          .ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS);
        if (company != null && !company.isBlank()) uw.eq(OpsBusinessData::getCompany, company.trim());
        if (batchNo != null && !batchNo.isBlank()) uw.eq(OpsBusinessData::getBatchNo, batchNo.trim());
        if (agent != null && !agent.isBlank()) uw.eq(OpsBusinessData::getAgent, agent.trim());
        if (receiptNo != null && !receiptNo.isBlank()) uw.eq(OpsBusinessData::getReceiptNo, receiptNo.trim());
        if (category != null && !category.isBlank()) uw.eq(OpsBusinessData::getCategory, category.trim());
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (visibleIds != null && !visibleIds.isEmpty()) uw.in(OpsBusinessData::getCorpId, visibleIds);
        return mapper.update(null, uw);
    }

    @Override
    public void exportCommissionSummary(String company, String batchNo, String agent, String receiptNo,
                                        String category, HttpServletResponse response) {
        try {
            // 先确保品类字典已加载：KEY_TO_CN 需含平台全量字典（如 气密性检测→qimi），
            // 否则汇总 Excel 品类列会显示原始 item_key 而非中文 item_value
            loadValidCategories();
            Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
            List<Long> corpIds = (visibleIds == null || visibleIds.isEmpty()) ? null : new ArrayList<>(visibleIds);
            List<Map<String, Object>> allRows = mapper.categoryCompanyStats(
                    company, batchNo, agent, receiptNo, category, corpIds);
            List<Map<String, Object>> monthRows = mapper.categoryCompanyStatsByMonth(
                    company, batchNo, agent, receiptNo, category, corpIds);
            String zipFile = buildCommissionSummaryZip(allRows, monthRows);
            String fileName = "品类提成汇总_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip";
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"=?UTF-8?B?"
                    + Base64.getEncoder().encodeToString(fileName.getBytes(StandardCharsets.UTF_8))
                    + "?=\"; filename*=UTF-8''" + URLEncoder.encode(fileName, "UTF-8"));
            Files.copy(Path.of(zipFile), response.getOutputStream());
            response.getOutputStream().flush();
            Files.deleteIfExists(Path.of(zipFile));
        } catch (Exception e) {
            log.error("品类提成汇总导出失败", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"汇总导出失败，请稍后重试\"}");
            } catch (Exception ignored) {}
        }
    }

    /** 双文件 ZIP：提成金额.xlsx + 销售金额.xlsx。每个文件 sheet 序 = 「全部汇总」+ 逐月
     *  （数据起始月 → 末尾月，按月提成/销售金额各品类汇总）。两文件共用同一品类×公司轴
     *  （取自全部汇总行），保证各 sheet 列对齐。返回 zip 临时文件路径。 */
    private String buildCommissionSummaryZip(List<Map<String, Object>> allRows,
                                             List<Map<String, Object>> monthRows) throws Exception {
        List<String> categoryKeys = collectCategoryKeys(allRows);
        List<String> companies = collectCompanies(allRows);
        Map<String, BigDecimal[]> cellAll = buildCellMap(allRows);
        List<String> months = collectMonths(monthRows);

        // cell 数组索引：0=销售额，1=提成金额（见 buildCellMap），按文件名各取对应值
        String commFile = writeSummaryXlsx(
                buildMonthlySheets(categoryKeys, companies, cellAll, monthRows, months, 1));
        String saleFile = writeSummaryXlsx(
                buildMonthlySheets(categoryKeys, companies, cellAll, monthRows, months, 0));

        String dateTag = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path zipPath = Files.createTempFile("commission_summary_", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            addZipEntry(zos, "提成金额汇总_" + dateTag + ".xlsx", commFile);
            addZipEntry(zos, "销售金额汇总_" + dateTag + ".xlsx", saleFile);
        } finally {
            Files.deleteIfExists(Path.of(commFile));
            Files.deleteIfExists(Path.of(saleFile));
        }
        return zipPath.toAbsolutePath().toString();
    }

    /** 数据中出现的销售月份（YYYY-MM），升序 = 起始月 → 末尾月。 */
    private List<String> collectMonths(List<Map<String, Object>> monthRows) {
        return monthRows.stream()
                .map(r -> String.valueOf(r.get("saleMonth")))
                .filter(m -> m != null && !m.isBlank() && !"null".equals(m))
                .distinct().sorted().toList();
    }

    /** 组装单个文件的 sheet 列表：全部汇总 + 逐月（月矩阵轴与全部汇总一致，缺值补 0）。 */
    private List<Map<String, Object>> buildMonthlySheets(List<String> categoryKeys, List<String> companies,
                                                         Map<String, BigDecimal[]> cellAll,
                                                         List<Map<String, Object>> monthRows,
                                                         List<String> months, int metricIdx) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(buildSummarySheet("全部汇总", categoryKeys, companies, cellAll, metricIdx));
        for (String m : months) {
            sheets.add(buildSummarySheet(m, categoryKeys, companies, buildCellMap(monthRows, m), metricIdx));
        }
        return sheets;
    }

    private void addZipEntry(ZipOutputStream zos, String entryName, String filePath) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(Path.of(filePath), zos);
        zos.closeEntry();
    }

    /** 品类行轴：CATEGORY_ORDER 固定顺序（户内美装三品类相邻）；未在映射中的品类追加在末尾。 */
    private List<String> collectCategoryKeys(List<Map<String, Object>> rows) {
        List<String> categoryKeys = new ArrayList<>();
        for (String k : CATEGORY_ORDER) {
            if (KEY_TO_CN.containsKey(k)) categoryKeys.add(k);
        }
        Set<String> presentCategories = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object c = r.get("category");
            if (c != null) presentCategories.add(String.valueOf(c));
        }
        for (String c : presentCategories) {
            if (!categoryKeys.contains(c)) categoryKeys.add(c);
        }
        return categoryKeys;
    }

    /** 公司列轴：按固定顺序，其余按名称排序追加（列头 = 组织简称 + "公司"）。 */
    private List<String> collectCompanies(List<Map<String, Object>> rows) {
        Set<String> presentCompanies = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object c = r.get("company");
            if (c != null) presentCompanies.add(String.valueOf(c));
        }
        List<String> companies = new ArrayList<>();
        for (String c : COMPANY_ORDER) if (presentCompanies.contains(c)) companies.add(c);
        List<String> extras = new ArrayList<>();
        for (String c : presentCompanies) if (!companies.contains(c)) extras.add(c);
        extras.sort(String::compareTo);
        companies.addAll(extras);
        return companies;
    }

    /** 单元格定位：category + "::" + company → {销售额, 提成金额}。 */
    private Map<String, BigDecimal[]> buildCellMap(List<Map<String, Object>> rows) {
        Map<String, BigDecimal[]> cell = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String key = String.valueOf(r.get("category")) + "::" + String.valueOf(r.get("company"));
            cell.put(key, new BigDecimal[]{
                    toDecimal(r.get("totalSale")), toDecimal(r.get("totalCommission"))});
        }
        return cell;
    }

    /** 单元格定位（按月过滤）：只取 saleMonth 等于指定月份的汇总行。 */
    private Map<String, BigDecimal[]> buildCellMap(List<Map<String, Object>> rows, String month) {
        Map<String, BigDecimal[]> cell = new HashMap<>();
        for (Map<String, Object> r : rows) {
            if (!month.equals(String.valueOf(r.get("saleMonth")))) continue;
            String key = String.valueOf(r.get("category")) + "::" + String.valueOf(r.get("company"));
            cell.put(key, new BigDecimal[]{
                    toDecimal(r.get("totalSale")), toDecimal(r.get("totalCommission"))});
        }
        return cell;
    }

    /** 组装单个 sheet 矩阵：表头 + 品类数据行 + 合计行（含行列合计）。 */
    private Map<String, Object> buildSummarySheet(String title, List<String> categoryKeys,
                                                  List<String> companies,
                                                  Map<String, BigDecimal[]> cell, int metricIdx) {
        List<Object> header = new ArrayList<>();
        header.add("序号"); header.add("品类");
        for (String c : companies) header.add(c + "公司");
        header.add("合计");
        List<List<Object>> dataRows = new ArrayList<>();
        List<Object> totalRow = new ArrayList<>();
        totalRow.add("合计"); totalRow.add("");
        BigDecimal[] colTotals = new BigDecimal[companies.size()];
        java.util.Arrays.fill(colTotals, BigDecimal.ZERO);
        BigDecimal grand = BigDecimal.ZERO;
        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (int i = 0; i < categoryKeys.size(); i++) {
            List<Object> row = new ArrayList<>();
            row.add(String.valueOf(i + 1)); row.add(getCategoryDisplayName(categoryKeys.get(i)));
            BigDecimal rowSum = BigDecimal.ZERO;
            for (int j = 0; j < companies.size(); j++) {
                BigDecimal v = cell.getOrDefault(categoryKeys.get(i) + "::" + companies.get(j), zero)[metricIdx];
                row.add(v.setScale(2, RoundingMode.HALF_UP));
                rowSum = rowSum.add(v);
                colTotals[j] = colTotals[j].add(v);
            }
            row.add(rowSum.setScale(2, RoundingMode.HALF_UP));
            grand = grand.add(rowSum);
            dataRows.add(row);
        }
        for (int j = 0; j < companies.size(); j++) totalRow.add(colTotals[j].setScale(2, RoundingMode.HALF_UP));
        totalRow.add(grand.setScale(2, RoundingMode.HALF_UP));
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("title", title);
        sheet.put("header", header);
        sheet.put("rows", dataRows);
        sheet.put("total", totalRow);
        return sheet;
    }

    /** JSON 数据 → python openpyxl 生成 xlsx（临时文件方式，避免 -c 转义问题）。返回 xlsx 路径。 */
    private String writeSummaryXlsx(List<Map<String, Object>> sheets) throws Exception {
        Path jsonFile = Files.createTempFile("commission_summary_", ".json");
        Path pyFile = Files.createTempFile("commission_summary_", ".py");
        Path xlsxFile = Path.of(jsonFile.toString().replace(".json", ".xlsx"));
        Files.writeString(jsonFile, JSON_MAPPER.writeValueAsString(sheets));
        Files.writeString(pyFile, SUMMARY_PYTHON_SCRIPT
                .replace("__JSON__", jsonFile.toAbsolutePath().toString())
                .replace("__XLSX__", xlsxFile.toAbsolutePath().toString()));
        int exit = new ProcessBuilder("python3", pyFile.toAbsolutePath().toString())
                .redirectErrorStream(true).start().waitFor();
        Files.deleteIfExists(jsonFile); Files.deleteIfExists(pyFile);
        if (exit != 0) throw new IllegalStateException("python3 openpyxl 执行失败, exit=" + exit);
        return xlsxFile.toAbsolutePath().toString();
    }

    private static BigDecimal toDecimal(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** 汇总 Excel 生成脚本：双 sheet 矩阵，表头/合计加粗、细边框、数字格式 #,##0.00，合计行合并 A:B */
    private static final String SUMMARY_PYTHON_SCRIPT = """
            import json, openpyxl
            from openpyxl.styles import Font, Alignment, Border, Side
            sheets = json.load(open('__JSON__', encoding='utf-8'))
            wb = openpyxl.Workbook()
            wb.remove(wb.active)
            thin = Side(style='thin', color='999999')
            border = Border(left=thin, right=thin, top=thin, bottom=thin)
            bold = Font(bold=True)
            for s in sheets:
                ws = wb.create_sheet(title=s['title'])
                ws.append(s['header'])
                for r in s['rows']:
                    ws.append(r)
                ws.append(s['total'])
                for row in ws.iter_rows(min_row=1, max_row=ws.max_row, min_col=1, max_col=len(s['header'])):
                    for cell in row:
                        cell.border = border
                        cell.alignment = Alignment(horizontal='center', vertical='center')
                        if isinstance(cell.value, float):
                            cell.number_format = '#,##0.00'
                for cell in ws[1]:
                    cell.font = bold
                last = ws.max_row
                for cell in ws[last]:
                    cell.font = bold
                ws.merge_cells(start_row=last, start_column=1, end_row=last, end_column=2)
                ws.column_dimensions['A'].width = 6
                ws.column_dimensions['B'].width = 20
                for col in range(3, len(s['header']) + 1):
                    ws.column_dimensions[openpyxl.utils.get_column_letter(col)].width = 14
            wb.save('__XLSX__')
            """;

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        // 锁定守卫：已上报集团的数据禁止删除（所有组织一致）
        long reported = mapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                .in(OpsBusinessData::getId, ids)
                .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS));
        if (reported > 0) {
            throw BusinessException.of("所选数据包含" + reported + "条已上报集团记录，已上报数据禁止删除");
        }
        // physical DELETE bypassing @TableLogic, but enforce corp_id scope
        String in = ids.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
        StringBuilder sql = new StringBuilder("DELETE FROM ops_business_data WHERE id IN (").append(in).append(")");
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (visibleIds != null && !visibleIds.isEmpty()) {
            String corpIn = visibleIds.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
            sql.append(" AND corp_id IN (").append(corpIn).append(")");
        }
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate(sql.toString());
        } catch (Exception e) { throw new RuntimeException("批量删除失败", e); }
    }

    @Override
    public int batchCheck(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) throw BusinessException.of("批次号不能为空");
        String sql = "UPDATE ops_business_data SET calc_status = '经办人已核对' WHERE batch_no = ? AND calc_status = '已计算'";
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchNo);
            return ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException("核对失败", e); }
    }

    /**
     * Append corp_id scope filter to query wrapper.
     * null visibleIds (super admin) → no filter added.
     */
    private void applyCorpScope(LambdaQueryWrapper<OpsBusinessData> qw) {
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (visibleIds != null && !visibleIds.isEmpty()) {
            qw.in(OpsBusinessData::getCorpId, visibleIds);
        }
    }

    // ── Org hierarchy rule inheritance ──

    /**
     * Build a corp→parentCorpId map for all corps in the given set.
     * Queries platform internal API; failures are logged and result in empty parent
     * (no inheritance) — safe fallback.
     */
    private Map<Long, Long> buildParentChainMap(Set<Long> corpIds) {
        Map<Long, Long> parentMap = new HashMap<>();
        for (Long id : corpIds) {
            if (id == 0L) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/internal/corp/" + id, null);
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null && data.get("parentCorpId") instanceof Number) {
                    parentMap.put(id, ((Number) data.get("parentCorpId")).longValue());
                }
            } catch (Exception e) {
                log.debug("Failed to resolve parent for corpId={}, using self-only rules: {}", id, e.getMessage());
            }
        }
        return parentMap;
    }

    /**
     * Resolve the parent corp ID for a single corp.
     * Calls platform internal API; returns null on failure — safe fallback.
     */
    private Long resolveParentId(Long corpId) {
        if (corpId == null || corpId == 0L) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/" + corpId, null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("parentCorpId") instanceof Number) {
                return ((Number) data.get("parentCorpId")).longValue();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve parent for corpId={}: {}", corpId, e.getMessage());
        }
        return null;
    }

    /**
     * Cascade parent rules down to child corps, plus platform-level rules (corpId=0)
     * to every corp. Uses putIfAbsent throughout — corp-specific rules always
     * take priority over inherited ones.
     * <p>Priority chain: own corp → parent → grandparent → corpId=0 (platform fallback)
     */
    private void cascadeParentRules(
            Map<Long, Map<String, OpsPersonalCommissionRule>> ruleMapByCorp,
            Map<Long, Long> parentMap) {
        // Collect all corp IDs that need rules (ensure entries exist for children
        // that have no rules of their own)
        Set<Long> allCorpIds = new HashSet<>(ruleMapByCorp.keySet());
        for (Long child : parentMap.keySet()) {
            allCorpIds.add(child);
        }
        for (Long corpId : allCorpIds) {
            ruleMapByCorp.computeIfAbsent(corpId, k -> new HashMap<>());
        }

        // Step 1: Cascade parent rules down the org hierarchy
        for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
            Long corpId = entry.getKey();
            Map<String, OpsPersonalCommissionRule> childRules = entry.getValue();

            // Walk up the parent chain
            Long parentId = parentMap.get(corpId);
            while (parentId != null && parentId != 0L) {
                Map<String, OpsPersonalCommissionRule> parentRules = ruleMapByCorp.get(parentId);
                if (parentRules != null) {
                    for (Map.Entry<String, OpsPersonalCommissionRule> pr : parentRules.entrySet()) {
                        childRules.putIfAbsent(pr.getKey(), pr.getValue());
                    }
                }
                parentId = parentMap.get(parentId); // walk up further
            }
        }

        // Step 2: Cascade platform-level rules (corpId=0) to ALL corps as last-resort fallback
        Map<String, OpsPersonalCommissionRule> platformRules = ruleMapByCorp.get(0L);
        if (platformRules != null && !platformRules.isEmpty()) {
            for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
                if (entry.getKey() == 0L) continue;
                for (Map.Entry<String, OpsPersonalCommissionRule> pr : platformRules.entrySet()) {
                    entry.getValue().putIfAbsent(pr.getKey(), pr.getValue());
                }
            }
            log.info("Platform-level rules (corpId=0) cascaded to {} corps as fallback",
                    ruleMapByCorp.size() - 1);
        }

        int inherited = 0;
        for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
            if (parentMap.containsKey(entry.getKey()) && !entry.getValue().isEmpty()) {
                inherited++;
            }
        }
        log.info("Rule inheritance cascaded: {} child corps received parent rules", inherited);
    }

    /**
     * Parse Excel to tab-separated text via Python/openpyxl.
     * Newlines inside cells are replaced with spaces to prevent the
     * one-row-per-line output contract from being broken.
     */
    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                        + "wb = openpyxl.load_workbook('" + file.toAbsolutePath() + "')\n"
                        + "ws = wb.active\n"
                        + "fills = {}\n"
                        + "for mg in ws.merged_cells.ranges:\n"
                        + "    val = ws.cell(mg.min_row, mg.min_col).value\n"
                        + "    for r in range(mg.min_row, mg.max_row + 1):\n"
                        + "        for c in range(mg.min_col, mg.max_col + 1):\n"
                        + "            fills[(r, c)] = val\n"
                        + "for row_idx, row in enumerate(ws.iter_rows(values_only=True), 1):\n"
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

}
