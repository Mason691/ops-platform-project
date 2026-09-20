package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.ValueAddedProductService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValueAddedProductServiceImpl implements ValueAddedProductService {

    private final OpsValueAddedProductMapper mapper;
    private final PlatformInternalClient platformInternalClient;

    private volatile Set<String> categoryCache;
    private static final Set<String> DEFAULT_CATEGORIES =
            Set.of("燃气灶", "抽油烟机", "壁挂炉", "热水器");

    /** 品类 itemKey→中文名 兜底映射（字典 API 不可达时使用），与 BusinessDataServiceImpl 静态字典一致 */
    private static final Map<String, String> DEFAULT_KEY_TO_CN = Map.ofEntries(
            Map.entry("1", "燃气灶"), Map.entry("2", "抽油烟机"),
            Map.entry("3", "热水器"), Map.entry("4", "壁挂炉"),
            Map.entry("5", "壁挂炉以旧换新销售"), Map.entry("6", "户内美装销售"),
            Map.entry("7", "定尺波纹管"), Map.entry("8", "户内美装工程"),
            Map.entry("9", "户内美装运维"), Map.entry("10", "保险"),
            Map.entry("11", "美装管道整改"), Map.entry("12", "美装安全评估"),
            Map.entry("qimi", "气密性检测"));

    private Set<String> getValidCategories() {
        if (categoryCache != null) return categoryCache;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
            Object data = resp.get("data");
            if (data instanceof List) {
                Set<String> set = new HashSet<>();
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        Object val = ((Map<?, ?>) item).get("itemKey");
                        if (val != null) set.add(val.toString());
                    }
                }
                if (!set.isEmpty()) { categoryCache = set; return set; }
            }
        } catch (Exception e) { log.warn("加载 PRODUCT_CATEGORY 字典失败", e); }
        return DEFAULT_CATEGORIES;
    }

    /** 每次导出实时拉取品类中文名（平台字典有缓存），避免静态缓存过期；失败回退兜底映射。 */
    private Map<String, String> getCategoryKeyToCn() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
            Object data = resp.get("data");
            if (data instanceof List) {
                Map<String, String> map = new HashMap<>();
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        Object k = ((Map<?, ?>) item).get("itemKey");
                        Object v = ((Map<?, ?>) item).get("itemValue");
                        if (k != null) map.put(k.toString(), v == null ? k.toString() : v.toString());
                    }
                }
                if (!map.isEmpty()) return map;
            }
        } catch (Exception e) { log.warn("加载 PRODUCT_CATEGORY 字典失败，导出使用兜底映射", e); }
        return DEFAULT_KEY_TO_CN;
    }

    private void validateCategory(String category) {
        if (category != null && !getValidCategories().contains(category)) {
            throw new BusinessException("品类必须为：" + String.join("/", getValidCategories()));
        }
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status) {
        LambdaQueryWrapper<OpsValueAddedProduct> qw = buildQuery(category, brand, model, status);

        // Manual count (avoid IPage.getTotal() returning 0 for non-BaseEntity)
        long total = mapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsValueAddedProduct> records = mapper.selectList(qw);
        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum);
    }

    @Override
    public void export(String category, String brand, String model, Integer status, HttpServletResponse response) {
        List<OpsValueAddedProduct> rows = mapper.selectList(buildQuery(category, brand, model, status));
        String baseName = "增值产品信息_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("product-export-");
            Path tsvFile = tmpDir.resolve(baseName + ".tsv");
            Path xlsxFile = tmpDir.resolve(baseName + ".xlsx");
            Files.writeString(tsvFile, buildTsv(rows, getCategoryKeyToCn()));
            boolean converted = convertToXlsx(tsvFile, xlsxFile);
            writeFileResponse(converted ? xlsxFile : tsvFile, baseName + (converted ? ".xlsx" : ".tsv"), converted, response);
        } catch (Exception e) {
            log.error("增值产品导出失败", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"导出失败，请稍后重试\"}");
            } catch (Exception ignored) { }
        } finally {
            if (tmpDir != null) {
                try { Files.deleteIfExists(tmpDir.resolve(baseName + ".tsv")); } catch (Exception ignored) { }
                try { Files.deleteIfExists(tmpDir.resolve(baseName + ".xlsx")); } catch (Exception ignored) { }
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) { }
            }
        }
    }

    /** TSV → XLSX（生产 Docker 内置 openpyxl）；环境缺失时返回 false 降级输出 TSV。 */
    private boolean convertToXlsx(Path tsvFile, Path xlsxFile) {
        try {
            int exit = new ProcessBuilder("python3", "-c",
                    "import openpyxl,sys\n"
                  + "wb=openpyxl.Workbook()\n"
                  + "ws=wb.active\n"
                  + "lines=open('" + tsvFile.toAbsolutePath() + "').read().strip().split('\\n')\n"
                  + "for line in lines:\n  ws.append(line.split('\\t'))\n"
                  + "wb.save('" + xlsxFile.toAbsolutePath() + "')\n"
            ).redirectErrorStream(true).start().waitFor();
            return exit == 0 && Files.exists(xlsxFile);
        } catch (Exception e) {
            log.warn("python3 openpyxl 转换失败，降级 TSV 导出", e);
            return false;
        }
    }

    private void writeFileResponse(Path file, String fileName, boolean xlsx, HttpServletResponse response) throws IOException {
        response.setContentType(xlsx
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/tab-separated-values;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(fileName.getBytes(StandardCharsets.UTF_8))
                + "?=\"; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        if (!xlsx) {
            // TSV 降级加 UTF-8 BOM，保证 Excel 直接打开中文不乱码
            response.getOutputStream().write(0xEF);
            response.getOutputStream().write(0xBB);
            response.getOutputStream().write(0xBF);
        }
        Files.copy(file, response.getOutputStream());
        response.getOutputStream().flush();
    }

    /** 列表与导出共享的筛选条件（含默认排序：品类升序 + id 升序） */
    private LambdaQueryWrapper<OpsValueAddedProduct> buildQuery(String category, String brand, String model, Integer status) {
        LambdaQueryWrapper<OpsValueAddedProduct> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) qw.eq(OpsValueAddedProduct::getCategory, category);
        if (brand != null && !brand.isEmpty()) qw.eq(OpsValueAddedProduct::getBrand, brand);
        if (model != null && !model.isEmpty()) qw.like(OpsValueAddedProduct::getModel, model.trim());
        if (status != null) qw.eq(OpsValueAddedProduct::getStatus, status);
        qw.orderByAsc(OpsValueAddedProduct::getCategory).orderByAsc(OpsValueAddedProduct::getId);
        return qw;
    }

    private String buildTsv(List<OpsValueAddedProduct> rows, Map<String, String> keyToCn) {
        StringBuilder sb = new StringBuilder();
        sb.append("品牌\t品类\t型号\t销售价\t零售底价\t集团最低价\t状态\t描述\t备注\n");
        for (OpsValueAddedProduct p : rows) {
            sb.append(fmt(p.getBrand())).append('\t')
              .append(keyToCn.getOrDefault(p.getCategory(), fmt(p.getCategory()))).append('\t')
              .append(fmt(p.getModel())).append('\t')
              .append(fmt(p.getSalePrice())).append('\t')
              .append(fmt(p.getRetailFloorPrice())).append('\t')
              .append(fmt(p.getGroupLowestPrice())).append('\t')
              .append(p.getStatus() != null && p.getStatus() == 1 ? "上架" : "下架").append('\t')
              .append(fmt(p.getDescription())).append('\t')
              .append(fmt(p.getRemark())).append('\n');
        }
        return sb.toString();
    }

    private String fmt(Object o) { return o == null ? "" : String.valueOf(o); }

    @Override
    public OpsValueAddedProduct getById(Long id) {
        OpsValueAddedProduct p = mapper.selectById(id);
        if (p == null) throw BusinessException.notFound("产品不存在");
        return p;
    }

    @Override
    @Transactional
    public void create(OpsValueAddedProduct product) {
        validateCategory(product.getCategory());
        if (product.getStatus() == null) product.setStatus(1);
        mapper.insert(product);
    }

    @Override
    @Transactional
    public void update(Long id, OpsValueAddedProduct product) {
        getById(id);
        validateCategory(product.getCategory());
        product.setId(id);
        mapper.updateById(product);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }
}
