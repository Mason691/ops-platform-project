package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.BusinessDataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDataServiceImpl implements BusinessDataService {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsPersonalCommissionRuleMapper ruleMapper;

    private static final Map<String, String> CN_TO_KEY = Map.of(
            "燃气灶", "1", "抽油烟机", "2", "热水器", "3", "壁挂炉", "4",
            "壁挂炉以旧换新销售", "5", "户内美装开发", "6", "定尺波纹管", "7",
            "户内美装工程", "8", "户内美装运维", "9"
    );

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo) {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        qw.orderByDesc(OpsBusinessData::getId);

        long total = mapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        return Map.of("records", mapper.selectList(qw), "total", total,
                "size", pageSize, "current", pageNum);
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
            // Save temp file
            tmpFile = Files.createTempFile("import_", ".xlsx");
            file.transferTo(tmpFile);

            // Load products
            List<OpsValueAddedProduct> prods = productMapper.selectList(null);
            Map<String, OpsValueAddedProduct> productMap = new HashMap<>();
            for (OpsValueAddedProduct p : prods) {
                productMap.put(p.getBrand() + "|" + p.getCategory() + "|" + p.getModel(), p);
            }

            // Load rules
            List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<OpsPersonalCommissionRule>().eq(OpsPersonalCommissionRule::getStatus, 1));
            Map<String, OpsPersonalCommissionRule> ruleMap = new HashMap<>();
            for (OpsPersonalCommissionRule r : rules) {
                ruleMap.putIfAbsent(r.getCategory(), r);
            }

            // Parse Excel via Python
            String json = parseExcelWithPython(tmpFile);
            if (json == null || json.isEmpty()) {
                return Map.of("success", 0, "fail", 0, "errors", List.of("Python解析Excel失败"));
            }

            // Split rows (tab-separated, newline-delimited)
            String[] rows = json.trim().split("\n");
            String[] headers = rows[0].split("\t");

            int companyIdx = indexOf(headers, "客户");
            int catIdx = indexOf(headers, "品类");
            int brandIdx = indexOf(headers, "品牌");
            int modelIdx = indexOf(headers, "型号");
            int priceIdx = indexOf(headers, "销售价");
            int agentIdx = indexOf(headers, "经办人");
            int saleDateIdx = indexOf(headers, "销售日期");
            int recDateIdx = indexOf(headers, "收款日期");
            int recNoIdx = indexOf(headers, "收据号");

            for (int i = 1; i < rows.length; i++) {
                String[] cols = rows[i].split("\t", -1);
                if (cols.length < 4) continue;
                try {
                    OpsBusinessData d = new OpsBusinessData();
                    d.setBatchNo(batchNo);
                    d.setCompany(val(cols, companyIdx));
                    d.setBrand(val(cols, brandIdx));
                    d.setModel(val(cols, modelIdx));
                    d.setSalePrice(toDecimal(val(cols, priceIdx)));
                    d.setAgent(val(cols, agentIdx));
                    d.setSaleDate(val(cols, saleDateIdx));
                    d.setReceiptDate(val(cols, recDateIdx));
                    d.setReceiptNo(val(cols, recNoIdx));
                    d.setOperatorId(4L);

                    String catCn = val(cols, catIdx);
                    d.setCategory(CN_TO_KEY.getOrDefault(catCn, catCn));

                    // Product reference prices
                    String pkey = d.getBrand() + "|" + d.getCategory() + "|" + d.getModel();
                    OpsValueAddedProduct prod = productMap.get(pkey);
                    if (prod != null) {
                        d.setRetailFloorPrice(prod.getRetailFloorPrice());
                        d.setGroupLowestPrice(prod.getGroupLowestPrice());
                    }

                    // Commission
                    OpsPersonalCommissionRule rule = ruleMap.get(d.getCategory());
                    if (d.getSalePrice() != null) {
                        BigDecimal[] cr = calcCommission(d.getSalePrice(),
                                d.getRetailFloorPrice(), d.getGroupLowestPrice(), rule);
                        d.setCommissionRate(cr[0]);
                        d.setCommissionAmount(cr[1]);
                    }

                    mapper.insert(d);
                    success++;
                } catch (Exception e) {
                    fail++;
                    errors.add("第" + (i + 1) + "行: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("导入Excel失败", e);
            errors.add("导入失败: " + e.getMessage());
        } finally {
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
        }
        return Map.of("success", success, "fail", fail, "errors", errors);
    }

    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl,json,sys\n"
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

    private BigDecimal[] calcCommission(BigDecimal salePrice, BigDecimal floorPrice,
                                         BigDecimal groupPrice, OpsPersonalCommissionRule rule) {
        BigDecimal zero = BigDecimal.ZERO;
        if (rule == null) return new BigDecimal[]{zero, zero};

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
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids != null && !ids.isEmpty()) mapper.deleteBatchIds(ids);
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
