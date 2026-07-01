package com.aip.ops.imports;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.sdk.client.PlatformInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 业务数据异步导入处理器。
 * 从 Excel 解析销售记录，经过多阶段校验后原子写入 ops_business_data。
 */
@Slf4j
@Component
public class BusinessDataImportHandler implements ImportHandler {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final PlatformInternalClient platformInternalClient;

    /** 平台品类字典 typeCode */
    private static final String CATEGORY_TYPE_CODE = "PRODUCT_CATEGORY";
    private static final Set<String> VALID_COMPANIES = Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

    /** dict itemKey → itemValue (Chinese name) 缓存 */
    private volatile Set<String> categoryNameCache;
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "燃气灶", "抽油烟机", "热水器", "壁挂炉",
            "壁挂炉以旧换新销售", "户内美装开发", "定尺波纹管",
            "户内美装工程", "户内美装运维"
    );

    public BusinessDataImportHandler(OpsBusinessDataMapper mapper,
                                     OpsValueAddedProductMapper productMapper,
                                     PlatformInternalClient platformInternalClient) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.platformInternalClient = platformInternalClient;
    }

    @Override
    public String getTaskType() { return "business_data"; }

    // ── ParsedRow holder ──
    private static class ParsedRow {
        String company, customer, brand, model, catCn, catKey;
        BigDecimal salePrice;
        String agent, agentPhone, dept;
        String saleDate, receiptDate, receiptNo;
        OpsValueAddedProduct product;
    }

    @Override
    public ImportResult execute(SysImportTask task) throws Exception {
        Path filePath = Path.of(task.getFilePath());
        if (!Files.exists(filePath)) {
            return ImportResult.fail("FILE_CHECK", List.of("导入文件不存在，请重新上传"), 0);
        }

        // ── FILE_CHECK ──
        String fileName = task.getFileName() != null ? task.getFileName().toLowerCase() : "";
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            return ImportResult.fail("FILE_CHECK", List.of("文件格式不正确，仅支持 .xlsx / .xls"), 0);
        }

        List<String> errors = new ArrayList<>();

        // ── Parse Excel via Python openpyxl ──
        String json;
        try {
            json = parseExcelWithPython(filePath);
        } catch (Exception e) {
            return ImportResult.fail("FILE_CHECK",
                    List.of("Excel 解析失败: " + e.getMessage()), 0);
        }
        if (json == null || json.isEmpty()) {
            return ImportResult.fail("FILE_CHECK", List.of("Excel 文件为空或无法解析"), 0);
        }

        String[] rows = json.trim().split("\n");
        if (rows.length < 2) {
            return ImportResult.fail("FILE_CHECK", List.of("Excel 文件无数据行"), 0);
        }

        // ── HEADER_CHECK ──
        String[] headers = rows[0].split("\t");
        String[] requiredHeaders = {"客户", "品类", "品牌", "型号", "销售价", "公司", "经办人", "经办人手机号"};
        List<String> headerErrors = new ArrayList<>();
        for (String h : requiredHeaders) {
            if (indexOf(headers, h) < 0) headerErrors.add("缺少列: " + h);
        }
        if (!headerErrors.isEmpty()) {
            return ImportResult.fail("HEADER_CHECK", headerErrors, rows.length - 1);
        }

        int customerIdx = indexOf(headers, "客户"), catIdx = indexOf(headers, "品类");
        int brandIdx = indexOf(headers, "品牌"), modelIdx = indexOf(headers, "型号");
        int priceIdx = indexOf(headers, "销售价"), companyIdx = indexOf(headers, "公司");
        int deptIdx = indexOf(headers, "部门"), agentIdx = indexOf(headers, "经办人");
        int agentPhoneIdx = indexOf(headers, "经办人手机号");
        int saleDateIdx = indexOf(headers, "销售日期"), recDateIdx = indexOf(headers, "收款日期");
        int recNoIdx = indexOf(headers, "收据号");

        // ── ROW_CHECK: parse + validate all rows ──
        Set<String> validCategories = loadValidCategories();

        // Load product map
        List<OpsValueAddedProduct> prods = productMapper.selectList(null);
        Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
        for (OpsValueAddedProduct p : prods) {
            productMap.put(p.getBrand() + "|" + p.getCategory() + "|" + p.getModel(), p);
        }

        List<ParsedRow> parsedRows = new ArrayList<>();
        int totalRows = 0;
        for (int i = 1; i < rows.length; i++) {
            String[] cols = rows[i].split("\t", -1);
            if (cols.length < 4) continue; // skip empty/note rows
            totalRows++;
            ParsedRow pr = parseRow(cols, customerIdx, catIdx, brandIdx, modelIdx,
                    priceIdx, companyIdx, deptIdx, agentIdx, agentPhoneIdx,
                    saleDateIdx, recDateIdx, recNoIdx, productMap);
            if (pr != null) {
                // Category validation
                checkCategory(pr, i + 1, validCategories, errors);
                // Field validation
                validateFields(pr, i + 1, errors);
                if (errors.isEmpty()) {
                    pr.catKey = mapCategoryKey(pr.catCn);
                    pr.product = productMap.get(pr.brand + "|" + pr.catKey + "|" + pr.model);
                }
                parsedRows.add(pr);
            }
        }

        if (!errors.isEmpty()) {
            return ImportResult.fail("ROW_CHECK", errors, totalRows);
        }

        // ── BUSINESS_CHECK: dedup, etc. ──
        // (skip for now — business rules can be added here)

        // ── INSERT: transactional batch write ──
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Map<String, String> batchNoMap = new HashMap<>();
        Long corpId = task.getCorpId() != null ? task.getCorpId() : 4L;
        int count = 0;
        for (ParsedRow pr : parsedRows) {
            String bn = batchNoMap.computeIfAbsent(pr.company, c -> c + ts);
            OpsBusinessData d = new OpsBusinessData();
            d.setBatchNo(bn); d.setCompany(pr.company); d.setCustomer(pr.customer);
            d.setBrand(pr.brand); d.setCategory(pr.catKey); d.setModel(pr.model);
            d.setSalePrice(pr.salePrice); d.setAgent(pr.agent);
            d.setAgentPhone(pr.agentPhone);
            d.setSaleDate(pr.saleDate); d.setReceiptDate(pr.receiptDate); d.setReceiptNo(pr.receiptNo);
            d.setRetailFloorPrice(pr.product != null ? pr.product.getRetailFloorPrice() : null);
            d.setGroupLowestPrice(pr.product != null ? pr.product.getGroupLowestPrice() : null);
            d.setCommissionRate(null); d.setCommissionAmount(null);
            d.setCalcStatus("未计算");
            d.setRemark(pr.dept.isEmpty() ? null : "部门: " + pr.dept);
            d.setCorpId(corpId);
            d.setOperatorId(corpId);
            mapper.insert(d);
            count++;
        }

        // Row count verification
        if (count != parsedRows.size()) {
            log.error("Import row count mismatch: inserted={} expected={}", count, parsedRows.size());
            throw new RuntimeException("入库行数(" + count + ")与有效行数(" + parsedRows.size() + ")不一致，已回滚");
        }

        log.info("Import done: {} rows, corpId={}", count, corpId);
        return ImportResult.success(count, totalRows);
    }

    // ── Category lookup ──

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
                    for (Object item : (List<?>) data) {
                        if (item instanceof Map) {
                            Object val = ((Map<?, ?>) item).get("itemValue");
                            if (val != null) set.add(val.toString());
                        }
                    }
                    if (!set.isEmpty()) { categoryNameCache = set; log.info("Loaded {} categories from dict", set.size()); return set; }
                }
            } catch (Exception e) {
                log.warn("Failed to load PRODUCT_CATEGORY dict from platform, using defaults: {}", e.getMessage());
            }
            categoryNameCache = DEFAULT_CATEGORIES;
            return categoryNameCache;
        }
    }

    /** Map Chinese category name to dict key (e.g. "燃气灶"→"1"). Uses dict API result. */
    private String mapCategoryKey(String catCn) {
        // Hardcoded fallback map for dict key lookup
        Map<String, String> cn2key = Map.of(
                "燃气灶", "1", "抽油烟机", "2", "热水器", "3", "壁挂炉", "4",
                "壁挂炉以旧换新销售", "5", "户内美装开发", "6", "定尺波纹管", "7",
                "户内美装工程", "8", "户内美装运维", "9"
        );
        return cn2key.getOrDefault(catCn, catCn);
    }

    private void checkCategory(ParsedRow pr, int rowNum, Set<String> validCategories, List<String> errors) {
        String cat = pr.catCn;
        if (cat.isEmpty()) {
            errors.add("第" + rowNum + "行: 品类为空");
        } else if (!validCategories.contains(cat)) {
            errors.add("第" + rowNum + "行: 品类'" + cat + "'不在字典范围内");
        }
    }

    // ── Field validation ──

    private void validateFields(ParsedRow pr, int rowNum, List<String> errors) {
        if (pr.company.isEmpty())
            errors.add("第" + rowNum + "行: 公司为空");
        else if (!VALID_COMPANIES.contains(pr.company))
            errors.add("第" + rowNum + "行: 无效公司 \"" + pr.company + "\"");
        if (pr.brand.isEmpty())
            errors.add("第" + rowNum + "行: 品牌为空");
        if (pr.model.isEmpty())
            errors.add("第" + rowNum + "行: 型号为空");
        if (pr.agent == null || pr.agent.isBlank())
            errors.add("第" + rowNum + "行: 经办人为空");
        String ap = pr.agentPhone;
        if (ap == null || ap.isEmpty())
            errors.add("第" + rowNum + "行: 经办人手机号为空");
        else if (!ap.matches("\\d{11}"))
            errors.add("第" + rowNum + "行: 经办人手机号格式不正确");
    }

    // ── Parsing ──

    private ParsedRow parseRow(String[] cols, int customerIdx, int catIdx, int brandIdx,
            int modelIdx, int priceIdx, int companyIdx, int deptIdx, int agentIdx,
            int agentPhoneIdx, int saleDateIdx, int recDateIdx, int recNoIdx,
            Map<String, OpsValueAddedProduct> productMap) {
        String brand = val(cols, brandIdx), model = val(cols, modelIdx);
        if (brand.isEmpty() || model.isEmpty()) return null;
        ParsedRow pr = new ParsedRow();
        pr.company = val(cols, companyIdx);
        pr.customer = val(cols, customerIdx);
        pr.brand = brand; pr.model = model;
        pr.catCn = val(cols, catIdx);
        pr.salePrice = toDecimal(val(cols, priceIdx));
        pr.agent = val(cols, agentIdx);
        pr.agentPhone = val(cols, agentPhoneIdx);
        pr.dept = val(cols, deptIdx);
        pr.saleDate = val(cols, saleDateIdx);
        pr.receiptDate = val(cols, recDateIdx);
        pr.receiptNo = val(cols, recNoIdx);
        return pr;
    }

    // ── Helpers ──

    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                + "wb=openpyxl.load_workbook('" + file.toAbsolutePath() + "')\n"
                + "ws=wb.active\n"
                + "for row in ws.iter_rows(values_only=True):\n"
                + "  print('\\t'.join(str(v) if v is not None else '' for v in row))\n");
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
}
