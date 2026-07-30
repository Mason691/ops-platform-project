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

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
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

    private static final Set<String> VALID_COMPANIES = Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

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
            "美装管道整改", "美装安全评估"
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

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo) {
        // Build visible corp IDs once
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> corpIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        // ── COUNT + STATS: single-row aggregate queries (no full table scan) ──
        long total = mapper.countFiltered(company, batchNo, agent, receiptNo, corpIdList);
        java.util.Map<String, java.math.BigDecimal> stats =
                mapper.aggregateStats(company, batchNo, agent, receiptNo, corpIdList);
        BigDecimal totalSale = stats != null && stats.get("totalSale") != null
                ? stats.get("totalSale") : BigDecimal.ZERO;
        BigDecimal totalCommission = stats != null && stats.get("totalCommission") != null
                ? stats.get("totalCommission") : BigDecimal.ZERO;

        // ── DATA: only fetch the requested page ──
        int offset = (pageNum - 1) * pageSize;
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
        if (receiptNo != null && !receiptNo.isEmpty()) qw.eq(OpsBusinessData::getReceiptNo, receiptNo);
        if (corpIdList != null) qw.in(OpsBusinessData::getCorpId, corpIdList);
        qw.orderByDesc(OpsBusinessData::getId);
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsBusinessData> records = mapper.selectList(qw);

        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum,
                "totalSale", totalSale.setScale(2, RoundingMode.HALF_UP),
                "totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
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

    /**
     * Parsed row holder — holds parsed cell values before validation and insertion.
     */
    private static class ParsedRow {
        String company, customer, brand, model, catCn, catKey;
        BigDecimal salePrice;
        String agent, agentPhone, dept;
        String saleDate, receiptDate, receiptNo;
        OpsValueAddedProduct product;
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

    /** Parse a single row into a ParsedRow. Only skip completely empty rows. */
    private ParsedRow parseRow(String[] cols, int customerIdx, int catIdx, int brandIdx,
            int modelIdx, int priceIdx, int companyIdx, int deptIdx, int agentIdx,
            int agentPhoneIdx, int saleDateIdx, int recDateIdx, int recNoIdx,
            Map<String, OpsValueAddedProduct> productMap) {
        String brand = val(cols, brandIdx), model = val(cols, modelIdx);
        String cat = val(cols, catIdx);
        // Only skip completely empty rows; non-appliance categories can lack brand/model
        if (cat.isEmpty() && brand.isEmpty() && model.isEmpty()) return null;

        ParsedRow pr = new ParsedRow();
        pr.company = val(cols, companyIdx);
        pr.customer = val(cols, customerIdx);
        pr.brand = brand;
        pr.model = model;
        pr.catCn = val(cols, catIdx);
        pr.salePrice = toDecimal(val(cols, priceIdx));
        pr.agent = val(cols, agentIdx);
        pr.agentPhone = val(cols, agentPhoneIdx);
        pr.dept = val(cols, deptIdx);
        pr.saleDate = normalizeDate(val(cols, saleDateIdx));
        pr.receiptDate = normalizeDate(val(cols, recDateIdx));
        pr.receiptNo = val(cols, recNoIdx);
        return pr;
    }

    /** Validate a parsed row and add any errors. */
    private void validateRow(ParsedRow pr, int rowNum, List<String> errors, Set<String> tieredCategories) {
        if (pr == null) return; // silently skip null rows (brand/model empty)
        // Category dict check
        String cat = pr.catCn;
        if (cat.isEmpty()) {
            errors.add("第" + rowNum + "行: 品类为空");
        } else {
            Set<String> valid = loadValidCategories();
            if (!valid.contains(cat)) {
                errors.add("第" + rowNum + "行: 品类'" + cat + "'不在字典范围内");
            }
        }
        if (pr.company.isEmpty())
            errors.add("第" + rowNum + "行: 公司为空");
        else if (!VALID_COMPANIES.contains(pr.company))
            errors.add("第" + rowNum + "行: 无效公司 \"" + pr.company + "\"");
        // Brand/model required only for tiered commission categories
        if (pr.catKey != null && tieredCategories.contains(pr.catKey)) {
            if (pr.brand.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，品牌不能为空");
            if (pr.model.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，型号不能为空");
        }
        if (pr.agent.isEmpty() || pr.agent.isBlank())
            errors.add("第" + rowNum + "行: 经办人为空");
        if (pr.salePrice == null)
            errors.add("第" + rowNum + "行: 销售价为空或格式不正确");
        else if (pr.salePrice.compareTo(java.math.BigDecimal.ZERO) < 0)
            errors.add("第" + rowNum + "行: 销售价不能为负数");
        String ap = pr.agentPhone;
        if (ap.isEmpty())
            errors.add("第" + rowNum + "行: 经办人手机号为空");
        else if (!ap.matches("\\d{11}"))
            errors.add("第" + rowNum + "行: 经办人手机号格式不正确");
    }

    /**
     * Validate only customer name consistency within the same receipt number.
     * Multiple products of different categories/brands/models/prices on one
     * receipt is normal business practice; we only flag genuinely conflicting
     * customer names.
     */
    private void validateReceiptGroups(List<ParsedRow> rows, List<String> errors) {
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
            int baseRowNum = baseIdx + 2;

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

    private int compareBigDecimal(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null || b == null) return -1;
        return a.compareTo(b);
    }

    /** Load categories with "阶梯" commission type from ops_personal_commission_rule. */
    private Set<String> loadTieredCommissionCategories(Long corpId) {
        try {
            List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<OpsPersonalCommissionRule>()
                            .eq(OpsPersonalCommissionRule::getCorpId, corpId)
                            .eq(OpsPersonalCommissionRule::getCommissionType, "阶梯")
                            .eq(OpsPersonalCommissionRule::getStatus, 1));
            if (rules.isEmpty()) return Set.of();
            Set<String> cats = new HashSet<>();
            for (OpsPersonalCommissionRule r : rules) {
                if (r.getCategory() != null && !r.getCategory().isBlank())
                    cats.add(r.getCategory());
            }
            return cats;
        } catch (Exception e) {
            log.warn("Failed to load tiered commission categories for corpId={}", corpId, e);
            return Set.of();
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
    @Transactional
    public Map<String, Object> calcCommission() {
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

        LambdaQueryWrapper<OpsBusinessData> uncalcQw = new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getCalcStatus, "未计算");
        applyCorpScope(uncalcQw);
        List<OpsBusinessData> uncalcs = mapper.selectList(uncalcQw);

        if (uncalcs.isEmpty()) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }

        // ── Phase 1: Compute per-row commission (in-memory, per-corp rule matching) ──
        Map<Long, BigDecimal[]> results = new LinkedHashMap<>();
        for (OpsBusinessData d : uncalcs) {
            Long dCorpId = d.getCorpId() != null ? d.getCorpId() : 0L;
            Map<String, OpsPersonalCommissionRule> corpRuleMap = ruleMapByCorp.getOrDefault(dCorpId,
                    ruleMapByCorp.getOrDefault(0L, Collections.emptyMap()));
            OpsPersonalCommissionRule rule = corpRuleMap.get(d.getCategory());
            BigDecimal[] cr = calcCommissionAmount(d.getSalePrice(),
                    d.getRetailFloorPrice(), d.getGroupLowestPrice(), rule);
            results.put(d.getId(), new BigDecimal[]{cr[0], cr[1]});
        }

        // ── Phase 2: Split by receipt group ──
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
     * Split commission evenly within receipt groups (N > 1).
     * The last row in each group absorbs rounding remainder so the
     * sum of adjusted amounts matches the original total exactly.
     *
     * @return number of receipt groups that were split
     */
    private int splitCommissionByReceiptGroup(List<OpsBusinessData> records,
                                               Map<Long, BigDecimal[]> results) {
        // Build receipt groups
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : records) {
            String rn = d.getReceiptNo();
            if (rn != null && !rn.isEmpty()) {
                groups.computeIfAbsent(rn, k -> new ArrayList<>()).add(d);
            }
        }

        int splitGroups = 0;
        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            List<OpsBusinessData> group = entry.getValue();
            if (group.size() <= 1) continue;
            splitGroups++;

            // Use FIRST row's commission as the total for this receipt order.
            // Since RECEIPT_CHECK guarantees consistent customer/category/
            // brand/model/salePrice within the group, any row gives the same
            // commission result.  We pick the first for traceability.
            BigDecimal[] firstResult = results.get(group.get(0).getId());
            BigDecimal totalAmount = (firstResult != null && firstResult[1] != null)
                    ? firstResult[1] : BigDecimal.ZERO;
            BigDecimal originalRate = (firstResult != null && firstResult[0] != null)
                    ? firstResult[0] : BigDecimal.ZERO;

            int n = group.size();
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
                OpsBusinessData d = group.get(i);
                BigDecimal finalAmount = (i < n - 1) ? baseAmount : remainder;
                results.put(d.getId(), new BigDecimal[]{perRowRate, finalAmount});
            }
        }
        return splitGroups;
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
                groups.computeIfAbsent(rn, k -> new ArrayList<>()).add(d);
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
            qw.orderByDesc(OpsBusinessData::getId);
            List<OpsBusinessData> all = mapper.selectList(qw);

            Path tsvFile = Files.createTempFile("export_", ".tsv");
            Path xlsFile = Path.of(tsvFile.toString().replace(".tsv", ".xlsx"));
            // Write header + rows as tab-separated
            StringBuilder sb = new StringBuilder();
            sb.append("批次\t公司\t客户\t品牌\t品类\t型号\t销售价\t零售底价\t集团最低价\t提成比例%\t提成金额\t状态\t经办人\t经办人手机号\t销售日期\t收款日期\t收据号\n");
            for (OpsBusinessData d : all) {
                sb.append(fmt(d.getBatchNo())).append('\t')
                  .append(fmt(d.getCompany())).append('\t').append(fmt(d.getCustomer())).append('\t')
                  .append(fmt(d.getBrand())).append('\t').append(getCategoryDisplayName(d.getCategory())).append('\t')
                  .append(fmt(d.getModel())).append('\t').append(fmt(d.getSalePrice())).append('\t')
                  .append(fmt(d.getRetailFloorPrice())).append('\t').append(fmt(d.getGroupLowestPrice())).append('\t')
                  .append(fmt(d.getCommissionRate())).append('\t').append(fmt(d.getCommissionAmount())).append('\t')
                  .append(fmt(d.getCalcStatus())).append('\t').append(fmt(d.getAgent())).append('\t')
                  .append(fmt(d.getAgentPhone())).append('\t')
                  .append(fmt(d.getSaleDate())).append('\t').append(fmt(d.getReceiptDate())).append('\t')
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

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
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

    private int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) if (arr[i].contains(key)) return i;
        return -1;
    }

    private String val(String[] arr, int idx) {
        return idx >= 0 && idx < arr.length ? arr[idx].trim() : "";
    }

    private BigDecimal toDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /**
     * Normalize a date string from openpyxl to YYYY-MM-DD.
     * openpyxl outputs datetime objects as "2026-06-30 00:00:00".
     * Returns empty string if input is blank.
     */
    private String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // openpyxl: "2026-06-30 00:00:00" → extract date part
        if (raw.contains(" ") && raw.length() >= 10) {
            String datePart = raw.substring(0, 10);
            if (datePart.matches("\\d{4}-\\d{2}-\\d{2}")) return datePart;
        }
        return raw.trim();
    }
}
