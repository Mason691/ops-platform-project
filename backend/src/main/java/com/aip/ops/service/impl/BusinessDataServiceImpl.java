package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.DataScopeService;
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

    private static final Set<String> VALID_COMPANIES = Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

    private static final Map<String, String> CN_TO_KEY = Map.of(
            "燃气灶", "1", "抽油烟机", "2", "热水器", "3", "壁挂炉", "4",
            "壁挂炉以旧换新销售", "5", "户内美装开发", "6", "定尺波纹管", "7",
            "户内美装工程", "8", "户内美装运维", "9"
    );

    /** Reverse map: dict key (1,2,3...) → Chinese display name (燃气灶,抽油烟机...) */
    public static final Map<String, String> KEY_TO_CN = Map.of(
            "1", "燃气灶", "2", "抽油烟机", "3", "热水器", "4", "壁挂炉",
            "5", "壁挂炉以旧换新销售", "6", "户内美装开发", "7", "定尺波纹管",
            "8", "户内美装工程", "9", "户内美装运维"
    );

    /** Category name cache from platform dict (volatile for DCL) */
    private volatile Set<String> categoryNameCache;
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "燃气灶", "抽油烟机", "热水器", "壁挂炉",
            "壁挂炉以旧换新销售", "户内美装开发", "定尺波纹管",
            "户内美装工程", "户内美装运维"
    );

    public BusinessDataServiceImpl(
            OpsBusinessDataMapper mapper,
            OpsValueAddedProductMapper productMapper,
            OpsPersonalCommissionRuleMapper ruleMapper,
            DataScopeService dataScopeService,
            DataSource dataSource,
            PlatformInternalClient platformInternalClient) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.ruleMapper = ruleMapper;
        this.dataScopeService = dataScopeService;
        this.dataSource = dataSource;
        this.platformInternalClient = platformInternalClient;
    }
    public static String getCategoryDisplayName(String key) {
        if (key == null || key.isEmpty()) return "";
        return KEY_TO_CN.getOrDefault(key, key);
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent) {
        // Build visible corp IDs once
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> corpIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        // ── COUNT + STATS: single-row aggregate queries (no full table scan) ──
        long total = mapper.countFiltered(company, batchNo, agent, corpIdList);
        java.util.Map<String, java.math.BigDecimal> stats =
                mapper.aggregateStats(company, batchNo, agent, corpIdList);
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
        List<String> errors = new ArrayList<>();
        Path tmpFile = null;

        try {
            tmpFile = Files.createTempFile("import_", ".xlsx");
            file.transferTo(tmpFile);

            // Load product map
            List<OpsValueAddedProduct> prods = productMapper.selectList(null);
            Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
            for (OpsValueAddedProduct p : prods) {
                productMap.put(p.getBrand() + "|" + p.getCategory() + "|" + p.getModel(), p);
            }

            String json = parseExcelWithPython(tmpFile);
            if (json == null || json.isEmpty()) {
                return Map.of("status", "fail", "totalErrors", 1,
                        "errors", List.of("Python解析Excel失败"));
            }

            String[] rows = json.trim().split("\n");
            if (rows.length < 2) {
                return Map.of("status", "fail", "totalErrors", 1,
                        "errors", List.of("Excel文件无数据行"));
            }

            String[] headers = rows[0].split("\t");
            int customerIdx = indexOf(headers, "客户"), catIdx = indexOf(headers, "品类");
            int brandIdx = indexOf(headers, "品牌"), modelIdx = indexOf(headers, "型号");
            int priceIdx = indexOf(headers, "销售价"), companyIdx = indexOf(headers, "公司");
            int deptIdx = indexOf(headers, "部门"), agentIdx = indexOf(headers, "经办人");
            int agentPhoneIdx = indexOf(headers, "经办人手机号");
            int saleDateIdx = indexOf(headers, "销售日期"), recDateIdx = indexOf(headers, "收款日期");
            int recNoIdx = indexOf(headers, "收据号");

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Map<String, String> batchNoMap = new HashMap<>();
            List<ParsedRow> parsedRows = new ArrayList<>();

            // ── Phase 1: Parse & validate all rows ──
            for (int i = 1; i < rows.length; i++) {
                String[] cols = rows[i].split("\t", -1);
                if (cols.length < 4) continue; // skip empty/note rows
                ParsedRow pr = parseRow(cols, customerIdx, catIdx, brandIdx, modelIdx,
                        priceIdx, companyIdx, deptIdx, agentIdx, agentPhoneIdx,
                        saleDateIdx, recDateIdx, recNoIdx, productMap);
                validateRow(pr, i + 1, errors);
                if (pr != null) {
                    // Resolve batchNo for company
                    pr.catKey = CN_TO_KEY.getOrDefault(pr.catCn, pr.catCn);
                    pr.product = productMap.get(pr.brand + "|" + pr.catKey + "|" + pr.model);
                    parsedRows.add(pr);
                }
            }

            // If any validation error → reject entirely
            if (!errors.isEmpty()) {
                return Map.of("status", "fail", "totalErrors", errors.size(), "errors", errors);
            }

            // ── Phase 1b: Receipt number group validation ──
            List<String> receiptErrors = new ArrayList<>();
            validateReceiptGroups(parsedRows, receiptErrors);
            if (!receiptErrors.isEmpty()) {
                return Map.of("status", "fail", "totalErrors",
                        receiptErrors.size(), "errors", receiptErrors);
            }

            // ── Phase 2: JDBC batch insert (single tx, DataSourceUtils participates in @Transactional) ──
            String insertSql = "INSERT INTO ops_business_data "
                    + "(batch_no, company, customer, brand, category, model, "
                    + "sale_price, agent, agent_phone, sale_date, receipt_date, receipt_no, "
                    + "retail_floor_price, group_lowest_price, calc_status, remark, "
                    + "corp_id, operator_id) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            int count;
            java.sql.Connection conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                count = 0;
                for (ParsedRow pr : parsedRows) {
                    String bn = batchNoMap.computeIfAbsent(pr.company, c -> c + ts);
                    int idx = 1;
                    ps.setString(idx++, bn);
                    ps.setString(idx++, pr.company);
                    ps.setString(idx++, pr.customer);
                    ps.setString(idx++, pr.brand);
                    ps.setString(idx++, pr.catKey);
                    ps.setString(idx++, pr.model);
                    ps.setBigDecimal(idx++, pr.salePrice);
                    ps.setString(idx++, pr.agent);
                    ps.setString(idx++, pr.agentPhone);
                    ps.setString(idx++, pr.saleDate);
                    ps.setString(idx++, pr.receiptDate);
                    ps.setString(idx++, pr.receiptNo);
                    ps.setBigDecimal(idx++, pr.product != null ? pr.product.getRetailFloorPrice() : null);
                    ps.setBigDecimal(idx++, pr.product != null ? pr.product.getGroupLowestPrice() : null);
                    ps.setString(idx++, "未计算");
                    ps.setString(idx++, pr.dept.isEmpty() ? null : "部门: " + pr.dept);
                    ps.setLong(idx++, corpId);
                    ps.setLong(idx++, corpId);
                    ps.addBatch();
                    count++;
                    if (count % 500 == 0) { ps.executeBatch(); }
                }
                if (count % 500 != 0) { ps.executeBatch(); }
            }

            log.info("导入Excel成功: {}条", count);
            return Map.of("status", "success", "count", count);

        } catch (Exception e) {
            log.error("导入Excel失败", e);
            return Map.of("status", "fail", "totalErrors", 1,
                    "errors", List.of("导入失败: " + e.getMessage()));
        } finally {
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
        }
    }

    /** Parse a single row into a ParsedRow. Returns null if brand/model empty (skip). */
    private ParsedRow parseRow(String[] cols, int customerIdx, int catIdx, int brandIdx,
            int modelIdx, int priceIdx, int companyIdx, int deptIdx, int agentIdx,
            int agentPhoneIdx, int saleDateIdx, int recDateIdx, int recNoIdx,
            Map<String, OpsValueAddedProduct> productMap) {
        String brand = val(cols, brandIdx), model = val(cols, modelIdx);
        if (brand.isEmpty() || model.isEmpty()) return null;

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
    private void validateRow(ParsedRow pr, int rowNum, List<String> errors) {
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
        if (pr.brand.isEmpty())
            errors.add("第" + rowNum + "行: 品牌为空");
        if (pr.model.isEmpty())
            errors.add("第" + rowNum + "行: 型号为空");
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
     * Validate that rows sharing the same receipt number have consistent
     * customer, category, brand, model, and sale price.
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
                if (!Objects.equals(base.catCn, comp.catCn)) {
                    errors.add("收据号" + receiptNo + "：第" + baseRowNum
                            + "行与第" + compRowNum + "行的品类不一致");
                }
                if (!Objects.equals(base.brand, comp.brand)) {
                    errors.add("收据号" + receiptNo + "：第" + baseRowNum
                            + "行与第" + compRowNum + "行的品牌不一致");
                }
                if (!Objects.equals(base.model, comp.model)) {
                    errors.add("收据号" + receiptNo + "：第" + baseRowNum
                            + "行与第" + compRowNum + "行的型号不一致");
                }
                if (compareBigDecimal(base.salePrice, comp.salePrice) != 0) {
                    errors.add("收据号" + receiptNo + "：第" + baseRowNum
                            + "行与第" + compRowNum + "行的销售价不一致");
                }
            }
        }
    }

    private int compareBigDecimal(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null || b == null) return -1;
        return a.compareTo(b);
    }

    /** Load valid category names from platform dict. Falls back to defaults on failure. */
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
                    for (Object item : (List<?>) data) {
                        if (item instanceof Map) {
                            Object val = ((Map<?, ?>) item).get("itemValue");
                            if (val != null) set.add(val.toString());
                        }
                    }
                    if (!set.isEmpty()) {
                        categoryNameCache = set;
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
        // Load rules (unchanged)
        List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<OpsPersonalCommissionRule>().eq(OpsPersonalCommissionRule::getStatus, 1));
        Map<String, OpsPersonalCommissionRule> ruleMap = new HashMap<>();
        for (OpsPersonalCommissionRule r : rules) ruleMap.putIfAbsent(r.getCategory(), r);

        LambdaQueryWrapper<OpsBusinessData> uncalcQw = new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getCalcStatus, "未计算");
        applyCorpScope(uncalcQw);
        List<OpsBusinessData> uncalcs = mapper.selectList(uncalcQw);

        if (uncalcs.isEmpty()) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }

        // ── Phase 1: Compute per-row commission (in-memory) ──
        Map<Long, BigDecimal[]> results = new LinkedHashMap<>();
        for (OpsBusinessData d : uncalcs) {
            OpsPersonalCommissionRule rule = ruleMap.get(d.getCategory());
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

    /**
     * Parse Excel to tab-separated text via Python/openpyxl.
     * Newlines inside cells are replaced with spaces to prevent the
     * one-row-per-line output contract from being broken.
     */
    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                        + "wb=openpyxl.load_workbook('" + file.toAbsolutePath() + "', read_only=True)\n"
                        + "ws=wb.active\n"
                        + "for row in ws.iter_rows(values_only=True):\n"
                        + "  print('\\t'.join(str(v).replace('\\n',' ').replace('\\r',' ') if v is not None else '' for v in row))\n");
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
