package com.aip.ops.imports;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.sdk.client.PlatformInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.*;

/**
 * 业务数据异步导入处理器。
 * 先全量校验所有行，全部通过后在单事务内用 JDBC batch 原子写入。
 * 任一行不合格则整个文件拒绝，不写入任何数据。
 */
@Slf4j
@Component
public class BusinessDataImportHandler implements ImportHandler {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsPersonalCommissionRuleMapper commissionRuleMapper;
    private final PlatformInternalClient platformInternalClient;
    private final DataSource dataSource;

    private static final String CATEGORY_TYPE_CODE = "PRODUCT_CATEGORY";
    private static final Set<String> VALID_COMPANIES =
            Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

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
                                     OpsPersonalCommissionRuleMapper commissionRuleMapper,
                                     PlatformInternalClient platformInternalClient,
                                     DataSource dataSource) {
        this.mapper = mapper;
        this.productMapper = productMapper;
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
        String receiptDateRaw; // raw value for validation (empty=ok, non-empty+null=error)
        OpsValueAddedProduct product;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult execute(SysImportTask task) throws Exception {
        Path filePath = Path.of(task.getFilePath());
        if (!Files.exists(filePath)) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("导入文件不存在，请重新上传"), 0);
        }

        // ── FILE_CHECK ──
        String fileName = task.getFileName() != null
                ? task.getFileName().toLowerCase() : "";
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("文件格式不正确，仅支持 .xlsx / .xls"), 0);
        }

        // ── Parse Excel ──
        String json;
        try {
            json = parseExcelWithPython(filePath);
        } catch (Exception e) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("Excel 解析失败: " + e.getMessage()), 0);
        }
        if (json == null || json.isEmpty()) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("Excel 文件为空或无法解析"), 0);
        }

        String[] rows = json.trim().split("\n");
        if (rows.length < 2) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("Excel 文件无数据行"), 0);
        }

        // ── HEADER_CHECK ──
        String[] headers = rows[0].split("\t");
        String[] requiredHeaders = {
            "客户", "品类", "品牌", "型号", "销售价", "公司", "经办人", "经办人手机号"
        };
        List<String> headerErrors = new ArrayList<>();
        for (String h : requiredHeaders) {
            if (indexOf(headers, h) < 0) headerErrors.add("缺少列: " + h);
        }
        if (!headerErrors.isEmpty()) {
            return ImportResult.fail("HEADER_CHECK", headerErrors, rows.length - 1);
        }

        int customerIdx = indexOf(headers, "客户");
        int catIdx = indexOf(headers, "品类");
        int brandIdx = indexOf(headers, "品牌");
        int modelIdx = indexOf(headers, "型号");
        int priceIdx = indexOf(headers, "销售价");
        int companyIdx = indexOf(headers, "公司");
        int deptIdx = indexOf(headers, "部门");
        int agentIdx = indexOf(headers, "经办人");
        int agentPhoneIdx = indexOf(headers, "经办人手机号");
        int saleDateIdx = indexOf(headers, "销售日期");
        int recDateIdx = indexOf(headers, "收款日期");
        int recNoIdx = indexOf(headers, "收据号");

        // Load categories from platform dict
        Set<String> validCategories = loadValidCategories();

        // Load product lookup map
        List<OpsValueAddedProduct> prods = productMapper.selectList(null);
        Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
        for (OpsValueAddedProduct p : prods) {
            productMap.put(p.getBrand() + "|" + p.getCategory() + "|"
                    + p.getModel(), p);
        }

        // ── Resolve tiered-commission categories (品牌/型号必填) ──
        Set<String> tieredCategories = loadTieredCommissionCategories(
                task.getCorpId() != null ? task.getCorpId() : 4L);

        // ── Phase 1: Parse + validate ALL rows ──
        List<ParsedRow> parsedRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int totalRows = 0;

        for (int i = 1; i < rows.length; i++) {
            String[] cols = rows[i].split("\t", -1);
            if (cols.length < 4) continue;
            totalRows++;

            ParsedRow pr = parseRow(cols, customerIdx, catIdx, brandIdx,
                    modelIdx, priceIdx, companyIdx, deptIdx, agentIdx,
                    agentPhoneIdx, saleDateIdx, recDateIdx, recNoIdx);
            if (pr != null) {
                checkCategory(pr, i + 1, validCategories, errors);
                // Resolve category key BEFORE validation (needed for tiered check)
                pr.catKey = mapCategoryKey(pr.catCn);
                validateFields(pr, i + 1, errors, tieredCategories);
                // Resolve product mapping (needed for INSERT phase)
                pr.product = productMap.get(pr.brand + "|" + pr.catKey
                        + "|" + pr.model);
                parsedRows.add(pr);
            }
        }

        // If ANY row failed validation → reject entire file
        if (!errors.isEmpty()) {
            return ImportResult.fail("ROW_CHECK", errors, totalRows);
        }

        // ── Phase 1b: Receipt number group validation ──
        List<String> receiptErrors = new ArrayList<>();
        validateReceiptGroups(parsedRows, receiptErrors);
        if (!receiptErrors.isEmpty()) {
            return ImportResult.fail("RECEIPT_CHECK", receiptErrors, totalRows);
        }

        // ── Phase 2: Single-transaction JDBC batch insert ──
        // DataSourceUtils returns the Spring-managed connection
        // (participates in @Transactional — all-or-nothing)
        String ts = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Map<String, String> batchNoMap = new HashMap<>();
        Long corpId = task.getCorpId() != null ? task.getCorpId() : 4L;

        String insertSql = "INSERT INTO ops_business_data "
                + "(batch_no, company, customer, brand, category, model, "
                + "sale_price, agent, agent_phone, sale_date, receipt_date, "
                + "receipt_no, retail_floor_price, group_lowest_price, "
                + "calc_status, remark, corp_id, operator_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        int batchCount = 0;
        PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(insertSql);
        try {
            for (ParsedRow pr : parsedRows) {
                String bn = batchNoMap.computeIfAbsent(pr.company,
                        c -> c + ts);

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
                ps.setBigDecimal(idx++, pr.product != null
                        ? pr.product.getRetailFloorPrice() : null);
                ps.setBigDecimal(idx++, pr.product != null
                        ? pr.product.getGroupLowestPrice() : null);
                ps.setString(idx++, "未计算");
                ps.setString(idx++, pr.dept.isEmpty() ? null
                        : "部门: " + pr.dept);
                ps.setLong(idx++, corpId);
                ps.setLong(idx++, corpId);

                ps.addBatch();
                batchCount++;

                if (batchCount % 500 == 0) {
                    ps.executeBatch();
                }
            }
            if (batchCount % 500 != 0) {
                ps.executeBatch();
            }

            if (batchCount != parsedRows.size()) {
                log.error("Row count mismatch: batch={} expected={}",
                        batchCount, parsedRows.size());
                throw new RuntimeException("入库行数(" + batchCount
                        + ")与有效行数(" + parsedRows.size() + ")不一致");
            }

            log.info("Import done: {} rows, corpId={}", batchCount, corpId);
            return ImportResult.success(batchCount, totalRows);
        } finally {
            ps.close();
        }
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
        // Brand/model required only for tiered commission categories (by category key)
        if (pr.catKey != null && tieredCategories.contains(pr.catKey)) {
            if (pr.brand.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，品牌不能为空");
            if (pr.model.isEmpty())
                errors.add("第" + rowNum + "行: 该品类存在阶梯提成规则，型号不能为空");
        }
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
        if (pr.receiptDateRaw != null && !pr.receiptDateRaw.isEmpty() && pr.receiptDate == null)
            errors.add("第" + rowNum + "行: 收款日期格式不正确（支持: 2026-06-23 / 6月23日 / 2026年6月23日）");
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

    private int compareBigDecimal(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null || b == null) return -1;
        return a.compareTo(b);
    }

    // ═══════ Parsing ═══════

    private ParsedRow parseRow(String[] cols, int customerIdx, int catIdx,
            int brandIdx, int modelIdx, int priceIdx, int companyIdx,
            int deptIdx, int agentIdx, int agentPhoneIdx, int saleDateIdx,
            int recDateIdx, int recNoIdx) {
        String brand = val(cols, brandIdx);
        String model = val(cols, modelIdx);
        String cat = val(cols, catIdx);
        // Only skip completely empty rows; non-appliance cats can lack brand/model
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
        // Normalize dates: convert Chinese/ISO/Excel formats → YYYY-MM-DD
        pr.saleDate = normalizeDate(val(cols, saleDateIdx));
        String rawReceipt = val(cols, recDateIdx);
        pr.receiptDateRaw = rawReceipt;
        pr.receiptDate = normalizeDate(rawReceipt);
        pr.receiptNo = val(cols, recNoIdx);
        return pr;
    }

    // ═══════ Helpers ═══════

    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                + "wb = openpyxl.load_workbook('"
                + file.toAbsolutePath() + "')\n"
                + "ws = wb.active\n"
                + "# Build fill map from merged cell ranges (MergedCell is read-only)\n"
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
        for (int i = 0; i < arr.length; i++)
            if (arr[i].contains(key)) return i;
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

    /** Parse a date string (already normalized or raw) for validation. */
    private java.time.LocalDate parseDate(String s) {
        s = s.trim();
        // Try ISO first
        try { return java.time.LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (Exception ignored) {}
        // Try Chinese
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").matcher(s);
        if (m.matches()) {
            return java.time.LocalDate.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        }
        throw new IllegalArgumentException("无法解析日期: " + s);
    }
}
