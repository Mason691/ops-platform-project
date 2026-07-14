package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsAnnualPlan;
import com.aip.ops.mapper.OpsAnnualPlanMapper;
import com.aip.ops.service.AnnualPlanService;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnualPlanServiceImpl implements AnnualPlanService {

    private final OpsAnnualPlanMapper mapper;
    private final DataScopeService dataScopeService;
    private final PlatformInternalClient platformClient;

    /** 当前年份 */
    private static final int CURRENT_YEAR = 2026;

    // ═══ 品类字典缓存 ═══
    private volatile Map<String, String> keyToLabelCache;
    private volatile Map<String, String> labelToKeyCache;

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, Integer year, Long orgId,
                                     String category, Integer status) {
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> orgIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        LambdaQueryWrapper<OpsAnnualPlan> qw = new LambdaQueryWrapper<>();
        qw.eq(year != null, OpsAnnualPlan::getYear, year != null ? year : CURRENT_YEAR);
        if (orgId != null) {
            qw.eq(OpsAnnualPlan::getOrgId, orgId);
        } else if (orgIdList != null) {
            qw.in(OpsAnnualPlan::getOrgId, orgIdList);
        }
        qw.eq(category != null && !category.isEmpty(), OpsAnnualPlan::getCategory, category);
        qw.eq(status != null, OpsAnnualPlan::getStatus, status);
        qw.orderByAsc(OpsAnnualPlan::getOrgId).orderByAsc(OpsAnnualPlan::getCategory);

        long total = mapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsAnnualPlan> records = mapper.selectList(qw);

        // 补充组织名称和品类名称
        List<Map<String, Object>> enrichedRecords = records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("year", r.getYear());
            m.put("orgId", r.getOrgId());
            m.put("orgName", resolveOrgShortName(r.getOrgId()));
            m.put("category", r.getCategory());
            m.put("categoryName", keyToLabel(r.getCategory()));
            m.put("planAmount", r.getPlanAmount());
            m.put("status", r.getStatus());
            m.put("remark", r.getRemark());
            m.put("operatorId", r.getOperatorId());
            m.put("createTime", r.getCreateTime());
            m.put("modifyTime", r.getModifyTime());
            return m;
        }).collect(Collectors.toList());

        return Map.of("records", enrichedRecords, "total", total,
                "size", pageSize, "current", pageNum);
    }

    @Override
    public OpsAnnualPlan getById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void create(OpsAnnualPlan plan) {
        // Validate orgId is within current operator's scope
        if (plan.getOrgId() == null) throw new RuntimeException("子公司不能为空");
        validateOrgInScope(plan.getOrgId());
        // Validate category
        if (plan.getCategory() == null || plan.getCategory().isEmpty())
            throw new RuntimeException("品类不能为空");
        if (labelToKey(plan.getCategory()) == null && keyToLabel(plan.getCategory()).equals(plan.getCategory()))
            throw new RuntimeException("品类不在字典中");
        // Validate amount
        if (plan.getPlanAmount() == null || plan.getPlanAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new RuntimeException("计划金额不能为负数");
        // Validate year
        if (plan.getYear() != null && (plan.getYear() < 2000 || plan.getYear() > 2100))
            throw new RuntimeException("年度不在有效范围");
        if (plan.getYear() == null) plan.setYear(CURRENT_YEAR);

        // Check for duplicates
        LambdaQueryWrapper<OpsAnnualPlan> existQw = new LambdaQueryWrapper<>();
        existQw.eq(OpsAnnualPlan::getYear, plan.getYear())
                .eq(OpsAnnualPlan::getOrgId, plan.getOrgId())
                .eq(OpsAnnualPlan::getCategory, plan.getCategory());
        if (mapper.selectCount(existQw) > 0)
            throw new RuntimeException("已存在相同年度+子公司+品类的计划");

        Long corpId = SecurityUtils.getCurrentCorpId();
        plan.setOperatorId(corpId);
        if (plan.getStatus() == null) plan.setStatus(0);
        mapper.insert(plan);
    }

    @Override
    @Transactional
    public void update(Long id, OpsAnnualPlan plan) {
        OpsAnnualPlan existing = mapper.selectById(id);
        if (existing == null) throw new RuntimeException("记录不存在");
        // Verify the record's org is within current operator's scope
        validateOrgInScope(existing.getOrgId());
        if (existing.getStatus() != null && existing.getStatus() == 1) {
            throw new RuntimeException("已审核的记录不可修改");
        }
        // If changing orgId, validate new orgId too
        if (plan.getOrgId() != null && !plan.getOrgId().equals(existing.getOrgId())) {
            validateOrgInScope(plan.getOrgId());
        }
        if (plan.getPlanAmount() != null && plan.getPlanAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new RuntimeException("计划金额不能为负数");

        LambdaUpdateWrapper<OpsAnnualPlan> uw = new LambdaUpdateWrapper<>();
        uw.eq(OpsAnnualPlan::getId, id);
        if (plan.getYear() != null) uw.set(OpsAnnualPlan::getYear, plan.getYear());
        if (plan.getOrgId() != null) uw.set(OpsAnnualPlan::getOrgId, plan.getOrgId());
        if (plan.getCategory() != null) uw.set(OpsAnnualPlan::getCategory, plan.getCategory());
        if (plan.getPlanAmount() != null) uw.set(OpsAnnualPlan::getPlanAmount, plan.getPlanAmount());
        if (plan.getRemark() != null) uw.set(OpsAnnualPlan::getRemark, plan.getRemark());
        uw.set(OpsAnnualPlan::getModifyTime, LocalDateTime.now());
        mapper.update(null, uw);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        OpsAnnualPlan existing = mapper.selectById(id);
        if (existing == null) throw new RuntimeException("记录不存在");
        validateOrgInScope(existing.getOrgId());
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public void approve(Long id) {
        OpsAnnualPlan existing = mapper.selectById(id);
        if (existing == null) throw new RuntimeException("记录不存在");
        validateOrgInScope(existing.getOrgId());
        LambdaUpdateWrapper<OpsAnnualPlan> uw = new LambdaUpdateWrapper<>();
        uw.eq(OpsAnnualPlan::getId, id)
          .set(OpsAnnualPlan::getStatus, 1)
          .set(OpsAnnualPlan::getModifyTime, LocalDateTime.now());
        mapper.update(null, uw);
    }

    @Override
    public Map<String, Object> summary(Integer year) {
        int y = year != null ? year : CURRENT_YEAR;
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> orgIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        BigDecimal totalAmount = mapper.sumTotalByYear(y, orgIdList);
        long orgCount = countOrgs(orgIdList);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", y);
        result.put("totalAmount", totalAmount);
        result.put("orgCount", orgCount);
        return result;
    }

    @Override
    public Map<String, Object> summaryByCategory(Integer year) {
        int y = year != null ? year : CURRENT_YEAR;
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> orgIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        List<Map<String, Object>> raw = mapper.sumByCategory(y, orgIdList);
        List<Map<String, Object>> enriched = raw.stream().map(m -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("category", m.get("category"));
            r.put("categoryName", keyToLabel((String) m.get("category")));
            r.put("total", m.get("total"));
            return r;
        }).collect(Collectors.toList());

        return Map.of("year", y, "categories", enriched);
    }

    @Override
    public Map<String, Object> orgDetail(Integer year) {
        int y = year != null ? year : CURRENT_YEAR;
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        List<Long> orgIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        List<Map<String, Object>> raw = mapper.orgDetail(y, orgIdList);
        // Transform underscore SQL keys to camelCase for frontend consistency
        List<Map<String, Object>> detail = raw.stream().map(m -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.get("id"));
            r.put("year", m.get("year"));
            r.put("orgId", m.get("org_id"));
            r.put("orgName", m.get("org_name"));
            r.put("category", m.get("category"));
            r.put("planAmount", m.get("plan_amount"));
            r.put("status", m.get("status"));
            r.put("remark", m.get("remark"));
            r.put("createTime", m.get("create_time"));
            r.put("modifyTime", m.get("modify_time"));
            return r;
        }).collect(Collectors.toList());

        return Map.of("year", y, "details", detail);
    }

    @Override
    @Transactional
    public Map<String, Object> importExcel(MultipartFile file, Long corpId) {
        List<String> errors = new ArrayList<>();
        int successCount = 0;
        Path tmpFile = null;

        try {
            // 保存临时文件供 Python 解析
            tmpFile = Files.createTempFile("annual_plan_import_", ".xlsx");
            file.transferTo(tmpFile.toFile());

            // 使用 Python openpyxl 解析 Excel（与 BusinessDataImportHandler 一致）
            String tsv = parseExcelWithPython(tmpFile);
            String[] lines = tsv.split("\n");
            if (lines.length < 2) {
                return Map.of("status", "fail", "totalErrors", 1,
                        "errors", List.of("Excel 文件无数据行（至少需要表头 + 1 行数据）"));
            }

            // 解析表头
            String[] headers = lines[0].split("\t");
            int orgCol = indexOf(headers, "子公司");
            int catCol = indexOf(headers, "品类");
            int amtCol = indexOf(headers, "计划金额");
            int rmkCol = indexOf(headers, "备注");

            if (orgCol < 0 || catCol < 0 || amtCol < 0) {
                return Map.of("status", "fail", "totalErrors", 1,
                        "errors", List.of("表头必须包含：子公司、品类、计划金额"));
            }

            ensureCategoryCache();
            Map<String, Long> orgNameToId = buildOrgNameMap();

            // 逐行解析（从第2行开始，跳过表头）
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split("\t", -1);

                try {
                    String orgName = colVal(cols, orgCol);
                    String catLabel = colVal(cols, catCol);
                    String amtStr = colVal(cols, amtCol);
                    String remark = rmkCol >= 0 ? colVal(cols, rmkCol) : null;

                    if (orgName.isEmpty()) {
                        errors.add("第" + (i + 1) + "行：子公司不能为空");
                        continue;
                    }
                    if (catLabel.isEmpty()) {
                        errors.add("第" + (i + 1) + "行：品类不能为空");
                        continue;
                    }

                    BigDecimal amount;
                    try {
                        amount = new BigDecimal(amtStr);
                        if (amount.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        errors.add("第" + (i + 1) + "行：计划金额格式错误「" + amtStr + "」");
                        continue;
                    }

                    Long orgId = orgNameToId.get(orgName.trim());
                    if (orgId == null) {
                        errors.add("第" + (i + 1) + "行：子公司「" + orgName + "」不存在");
                        continue;
                    }

                    String catKey = labelToKey(catLabel.trim());
                    if (catKey == null) {
                        errors.add("第" + (i + 1) + "行：品类「" + catLabel + "」不在字典中");
                        continue;
                    }

                    // 检查是否已存在同年度同组织同品类记录
                    LambdaQueryWrapper<OpsAnnualPlan> existQw = new LambdaQueryWrapper<>();
                    existQw.eq(OpsAnnualPlan::getYear, CURRENT_YEAR)
                           .eq(OpsAnnualPlan::getOrgId, orgId)
                           .eq(OpsAnnualPlan::getCategory, catKey);
                    OpsAnnualPlan exist = mapper.selectOne(existQw);

                    if (exist != null) {
                        // Re-import resets approval status — new data needs re-approval
                        LambdaUpdateWrapper<OpsAnnualPlan> uw = new LambdaUpdateWrapper<>();
                        uw.eq(OpsAnnualPlan::getId, exist.getId())
                          .set(OpsAnnualPlan::getPlanAmount, amount)
                          .set(OpsAnnualPlan::getStatus, 0)
                          .set(OpsAnnualPlan::getModifyTime, LocalDateTime.now());
                        if (remark != null && !remark.isEmpty()) {
                            uw.set(OpsAnnualPlan::getRemark, remark);
                        }
                        mapper.update(null, uw);
                    } else {
                        OpsAnnualPlan plan = new OpsAnnualPlan();
                        plan.setYear(CURRENT_YEAR);
                        plan.setOrgId(orgId);
                        plan.setCategory(catKey);
                        plan.setPlanAmount(amount);
                        plan.setStatus(0);
                        plan.setRemark(remark != null && !remark.isEmpty() ? remark : null);
                        plan.setOperatorId(corpId);
                        mapper.insert(plan);
                    }
                    successCount++;
                } catch (Exception e) {
                    errors.add("第" + (i + 1) + "行：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Excel import failed", e);
            return Map.of("status", "fail", "totalErrors", 1,
                    "errors", List.of("文件解析失败：" + e.getMessage()));
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }

        if (!errors.isEmpty()) {
            return Map.of("status", "fail", "totalErrors", errors.size(),
                    "errors", errors, "count", successCount);
        }
        return Map.of("status", "success", "count", successCount);
    }

    // ═══ 私有辅助方法 ═══

    /** 校验 orgId 是否在当前用户的管辖范围内 */
    private void validateOrgInScope(Long orgId) {
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        // superadmin: visibleIds is null → allow all
        if (visibleIds == null) return;
        if (!visibleIds.contains(orgId)) {
            throw new RuntimeException("无权操作该组织的数据");
        }
    }

    /** 使用 Python openpyxl 解析 Excel 为 TSV */
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
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].contains(key)) return i;
        }
        return -1;
    }

    private String colVal(String[] arr, int idx) {
        return idx >= 0 && idx < arr.length ? arr[idx].trim() : "";
    }

    /** 统计可见组织数量（去重 org_id） */
    private long countOrgs(List<Long> orgIdList) {
        LambdaQueryWrapper<OpsAnnualPlan> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsAnnualPlan::getYear, CURRENT_YEAR)
          .eq(OpsAnnualPlan::getStatus, 1)
          .select(OpsAnnualPlan::getOrgId);
        if (orgIdList != null) {
            qw.in(OpsAnnualPlan::getOrgId, orgIdList);
        }
        return mapper.selectList(qw).stream()
                .map(OpsAnnualPlan::getOrgId).distinct().count();
    }

    /** 解析组织短名称 */
    private String resolveOrgShortName(Long orgId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/" + orgId, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null) {
                Object sn = data.get("shortName");
                return sn != null ? sn.toString() : String.valueOf(orgId);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve org shortName for orgId={}: {}", orgId, e.getMessage());
        }
        return String.valueOf(orgId);
    }

    /** 构建子公司名称 → corpId 映射 */
    private Map<String, Long> buildOrgNameMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/list-by-role?roleName=城市合伙人&pageNum=1&pageSize=999", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("records") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
                for (Map<String, Object> r : records) {
                    Object idObj = r.get("id");
                    Object nameObj = r.get("shortName");
                    if (nameObj instanceof String && !((String) nameObj).isEmpty()) {
                        map.put(((String) nameObj).trim(),
                                idObj instanceof Number ? ((Number) idObj).longValue() : null);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build org name map: {}", e.getMessage());
        }
        return map;
    }

    private String keyToLabel(String key) {
        ensureCategoryCache();
        return keyToLabelCache.getOrDefault(key, key);
    }

    private String labelToKey(String label) {
        ensureCategoryCache();
        return labelToKeyCache.get(label);
    }

    /** 从平台字典加载品类映射（type_id=9） */
    private synchronized void ensureCategoryCache() {
        if (keyToLabelCache != null) return;
        Map<String, String> k2l = new LinkedHashMap<>();
        Map<String, String> l2k = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/dict/type/9", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
                if (items != null) {
                    for (Map<String, Object> item : items) {
                        String key = String.valueOf(item.get("itemKey"));
                        String label = String.valueOf(item.get("itemValue"));
                        k2l.put(key, label);
                        l2k.put(label, key);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load category dict, using fallback: {}", e.getMessage());
            k2l.putAll(FALLBACK_CATEGORIES);
            FALLBACK_CATEGORIES.forEach((k, v) -> l2k.put(v, k));
        }
        keyToLabelCache = k2l;
        labelToKeyCache = l2k;
    }

    private static final Map<String, String> FALLBACK_CATEGORIES = Map.ofEntries(
            Map.entry("1", "燃气灶"), Map.entry("2", "抽油烟机"),
            Map.entry("3", "热水器"), Map.entry("4", "壁挂炉"),
            Map.entry("5", "壁挂炉以旧换新销售"), Map.entry("6", "户内美装销售"),
            Map.entry("7", "定尺波纹管"), Map.entry("8", "户内美装工程"),
            Map.entry("9", "户内美装运维"), Map.entry("10", "保险"),
            Map.entry("11", "美装管道整改"), Map.entry("12", "美装安全评估")
    );
}
