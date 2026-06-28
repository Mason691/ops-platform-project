package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.BusinessDataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Statement;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDataServiceImpl implements BusinessDataService {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsPersonalCommissionRuleMapper ruleMapper;
    private final DataSource dataSource;

    private static final Set<String> VALID_COMPANIES = Set.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

    private static final Map<String, String> CN_TO_KEY = Map.of(
            "燃气灶", "1", "抽油烟机", "2", "热水器", "3", "壁挂炉", "4",
            "壁挂炉以旧换新销售", "5", "户内美装开发", "6", "定尺波纹管", "7",
            "户内美装工程", "8", "户内美装运维", "9"
    );

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent) {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
        qw.orderByDesc(OpsBusinessData::getId);

        long total = mapper.selectCount(qw);

        // Aggregate stats for current filter
        List<OpsBusinessData> all = mapper.selectList(qw);
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        for (OpsBusinessData d : all) {
            if (d.getSalePrice() != null) totalSale = totalSale.add(d.getSalePrice());
            if (d.getCommissionAmount() != null) totalCommission = totalCommission.add(d.getCommissionAmount());
        }

        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsBusinessData> records = mapper.selectList(qw);

        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum,
                "totalSale", totalSale.setScale(2, RoundingMode.HALF_UP),
                "totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
    }

    @Override
    public List<String> getBatches() {
        List<OpsBusinessData> all = mapper.selectList(new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getBatchNo)
                .groupBy(OpsBusinessData::getBatchNo)
                .orderByDesc(OpsBusinessData::getBatchNo));
        Set<String> set = new LinkedHashSet<>();
        for (OpsBusinessData d : all) if (d.getBatchNo() != null) set.add(d.getBatchNo());
        return new ArrayList<>(set);
    }

    @Override
    @Transactional
    public Map<String, Object> importExcel(MultipartFile file, String batchNo) {
        int success = 0, fail = 0;
        List<String> errors = new ArrayList<>();
        Path tmpFile = null;

        try {
            tmpFile = Files.createTempFile("import_", ".xlsx");
            file.transferTo(tmpFile);

            List<OpsValueAddedProduct> prods = productMapper.selectList(null);
            Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
            for (OpsValueAddedProduct p : prods) {
                productMap.put(p.getBrand() + "|" + p.getCategory() + "|" + p.getModel(), p);
            }

            String json = parseExcelWithPython(tmpFile);
            if (json == null || json.isEmpty()) {
                return Map.of("success", 0, "fail", 0, "errors", List.of("Python解析Excel失败"));
            }

            String[] rows = json.trim().split("\n");
            String[] headers = rows[0].split("\t");
            int customerIdx = indexOf(headers, "客户"), catIdx = indexOf(headers, "品类");
            int brandIdx = indexOf(headers, "品牌"), modelIdx = indexOf(headers, "型号");
            int priceIdx = indexOf(headers, "销售价"), companyIdx = indexOf(headers, "公司");
            int deptIdx = indexOf(headers, "部门"), agentIdx = indexOf(headers, "经办人");
            int saleDateIdx = indexOf(headers, "销售日期"), recDateIdx = indexOf(headers, "收款日期");
            int recNoIdx = indexOf(headers, "收据号");

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Map<String, String> batchNoMap = new HashMap<>();

            for (int i = 1; i < rows.length; i++) {
                String[] cols = rows[i].split("\t", -1);
                if (cols.length < 4) continue;
                try {
                    String company = val(cols, companyIdx), customer = val(cols, customerIdx);
                    String brand = val(cols, brandIdx), model = val(cols, modelIdx);
                    String catCn = val(cols, catIdx);
                    BigDecimal salePrice = toDecimal(val(cols, priceIdx));
                    String agent = val(cols, agentIdx), dept = val(cols, deptIdx);
                    String saleDate = val(cols, saleDateIdx), recDate = val(cols, recDateIdx), recNo = val(cols, recNoIdx);

                    if (brand.isEmpty() || model.isEmpty()) continue;
                    if (company.isEmpty()) { fail++; errors.add("第" + (i + 1) + "行: 公司为空"); continue; }
                    if (!VALID_COMPANIES.contains(company)) { fail++; errors.add("第" + (i + 1) + "行: 无效公司 \"" + company + "\""); continue; }

                    String bn = batchNoMap.computeIfAbsent(company, c -> c + ts);
                    String catKey = CN_TO_KEY.getOrDefault(catCn, catCn);
                    OpsValueAddedProduct prod = productMap.get(brand + "|" + catKey + "|" + model);

                    OpsBusinessData d = new OpsBusinessData();
                    d.setBatchNo(bn); d.setCompany(company); d.setCustomer(customer);
                    d.setBrand(brand); d.setCategory(catKey); d.setModel(model);
                    d.setSalePrice(salePrice); d.setAgent(agent);
                    d.setSaleDate(saleDate); d.setReceiptDate(recDate); d.setReceiptNo(recNo);
                    d.setRetailFloorPrice(prod != null ? prod.getRetailFloorPrice() : null);
                    d.setGroupLowestPrice(prod != null ? prod.getGroupLowestPrice() : null);
                    d.setCommissionRate(null); d.setCommissionAmount(null);
                    d.setCalcStatus("未计算");
                    d.setRemark(dept.isEmpty() ? null : "部门: " + dept);
                    d.setOperatorId(4L);

                    mapper.insert(d);
                    success++;
                } catch (Exception e) { fail++; errors.add("第" + (i + 1) + "行: " + e.getMessage()); }
            }
        } catch (Exception e) { log.error("导入Excel失败", e); errors.add("导入失败: " + e.getMessage()); }
        finally { if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {} }
        return Map.of("success", success, "fail", fail, "errors", errors);
    }

    @Override
    @Transactional
    public Map<String, Object> calcCommission() {
        // Load all rules
        List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<OpsPersonalCommissionRule>().eq(OpsPersonalCommissionRule::getStatus, 1));
        Map<String, OpsPersonalCommissionRule> ruleMap = new HashMap<>();
        for (OpsPersonalCommissionRule r : rules) ruleMap.putIfAbsent(r.getCategory(), r);

        // Process uncalculated records
        List<OpsBusinessData> uncalcs = mapper.selectList(
                new LambdaQueryWrapper<OpsBusinessData>().eq(OpsBusinessData::getCalcStatus, "未计算"));

        int done = 0, zero = 0;
        for (OpsBusinessData d : uncalcs) {
            OpsPersonalCommissionRule rule = ruleMap.get(d.getCategory());
            BigDecimal[] cr = calcCommissionAmount(d.getSalePrice(),
                    d.getRetailFloorPrice(), d.getGroupLowestPrice(), rule);
            LambdaUpdateWrapper<OpsBusinessData> uw = new LambdaUpdateWrapper<>();
            uw.set(OpsBusinessData::getCommissionRate, cr[0])
              .set(OpsBusinessData::getCommissionAmount, cr[1])
              .set(OpsBusinessData::getCalcStatus, "已计算")
              .eq(OpsBusinessData::getId, d.getId());
            mapper.update(null, uw);
            if (cr[1].compareTo(BigDecimal.ZERO) > 0) done++; else zero++;
        }
        log.info("提成计算完成: {}条有提成, {}条0提成, 共{}条", done, zero, uncalcs.size());
        return Map.of("total", uncalcs.size(), "commissioned", done, "zero", zero);
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
            qw.orderByDesc(OpsBusinessData::getId);
            List<OpsBusinessData> all = mapper.selectList(qw);

            Path tmp = Files.createTempFile("export_", ".xlsx");
            StringBuilder sb = new StringBuilder();
            sb.append("批次\t公司\t客户\t品牌\t品类\t型号\t销售价\t零售底价\t集团最低价\t提成比例%\t提成金额\t状态\t经办人\t销售日期\t收款日期\t收据号\n");
            for (OpsBusinessData d : all) {
                sb.append(fmt(d.getBatchNo())).append('\t')
                  .append(fmt(d.getCompany())).append('\t').append(fmt(d.getCustomer())).append('\t')
                  .append(fmt(d.getBrand())).append('\t').append(fmt(d.getCategory())).append('\t')
                  .append(fmt(d.getModel())).append('\t').append(fmt(d.getSalePrice())).append('\t')
                  .append(fmt(d.getRetailFloorPrice())).append('\t').append(fmt(d.getGroupLowestPrice())).append('\t')
                  .append(fmt(d.getCommissionRate())).append('\t').append(fmt(d.getCommissionAmount())).append('\t')
                  .append(fmt(d.getCalcStatus())).append('\t').append(fmt(d.getAgent())).append('\t')
                  .append(fmt(d.getSaleDate())).append('\t').append(fmt(d.getReceiptDate())).append('\t')
                  .append(fmt(d.getReceiptNo())).append('\n');
            }
            String script = "import openpyxl\nwb=openpyxl.Workbook()\nws=wb.active\ndata=\"\"\"" + sb + "\"\"\"\nfor line in data.strip().split('\\n'):\n  ws.append(line.split('\\t'))\nwb.save('" + tmp.toAbsolutePath() + "')\n";
            new ProcessBuilder("python3", "-c", script).redirectErrorStream(true).start().waitFor();

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=业务数据导出.xlsx");
            Files.copy(tmp, response.getOutputStream());
            response.getOutputStream().flush();
            Files.deleteIfExists(tmp);
        } catch (Exception e) { log.error("导出失败", e); }
    }

    private String fmt(Object o) { return o == null ? "" : String.valueOf(o); }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        // physical DELETE bypassing @TableLogic
        String in = ids.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM ops_business_data WHERE id IN (" + in + ")");
        } catch (Exception e) { throw new RuntimeException("批量删除失败", e); }
    }

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
