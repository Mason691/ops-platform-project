package com.aip.ops.imports;

import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.OpsGasUser;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsGasUserMapper;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.util.AmapGeocodeUtil;
import com.aip.sdk.client.PlatformInternalClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 燃气平台用户异步导入处理器。
 * 从外部燃气数据库 jinyuan_info.sb_gas_user 读取数据，
 * 以 phone + gas_supply_certificate_number 联合去重后增量写入 ops_gas_user。
 * 自动创建缺失小区（含高德地理编码 + 地区匹配）。
 * 采用事务内 JDBC batch INSERT，任一步失败全部回滚。
 */
@Slf4j
@Component
public class GasUserImportHandler implements ImportHandler {

    private final OpsGasUserMapper gasUserMapper;
    private final JdbcTemplate localJdbcTemplate; // 本地 mall_db（含 ai_platform 跨库）
    private final GasDataSourceConfig gasDataSourceConfig; // 外部 jinyuan_info
    private final DataSource dataSource;
    private final AmapGeocodeUtil amapGeocodeUtil;
    private final ImportTaskService taskService;
    private final PlatformInternalClient platformInternalClient;
    private final String companyFilter;

    private static final String IMPORT_BATCH_PREFIX = "GAS_IMP_";

    public GasUserImportHandler(
            OpsGasUserMapper gasUserMapper,
            JdbcTemplate localJdbcTemplate,
            GasDataSourceConfig gasDataSourceConfig,
            DataSource dataSource,
            AmapGeocodeUtil amapGeocodeUtil,
            ImportTaskService taskService,
            PlatformInternalClient platformInternalClient,
            @Qualifier("gasCompanyFilter") String companyFilter) {
        this.gasUserMapper = gasUserMapper;
        this.localJdbcTemplate = localJdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.dataSource = dataSource;
        this.amapGeocodeUtil = amapGeocodeUtil;
        this.taskService = taskService;
        this.platformInternalClient = platformInternalClient;
        this.companyFilter = companyFilter;
    }

    @Override
    public String getTaskType() {
        return "gas_user";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult execute(SysImportTask task) throws Exception {
        Long corpId = task.getCorpId();
        String batchNo = IMPORT_BATCH_PREFIX + task.getId();

        // ── Phase 1: 从外部 DB 读取源数据 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "READ_EXTERNAL",
                "正在从外部燃气系统读取数据...");
        // Read company name from task.remark (set by controller from user input)
        String company = task.getRemark();
        if (company == null || company.isBlank()) company = companyFilter;
        String sql = "SELECT * FROM sb_gas_user WHERE area_name LIKE CONCAT(?, '%')";
        log.warn("GasUserImport: executing SQL=[{}] with company=[{}]", sql, company);
        JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
        List<Map<String, Object>> srcRows;
        try {
            srcRows = gasJdbc.queryForList(sql, company);
        } catch (Exception e) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("无法连接外部燃气数据库: " + e.getMessage()), 0);
        }
        if (srcRows.isEmpty()) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("外部燃气系统中未找到符合条件的用户数据"), 0);
        }
        log.warn("GasUserImport: company=[{}] SQL totalRows={}", company, srcRows.size());

        // ── Phase 2: 查询现有去重集合 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "DEDUP",
                "正在查询已有用户，构建去重集合...");
        Set<String> existingKeys = loadExistingDedupKeys();
        log.warn("GasUserImport: {} existing dedup keys in ops_gas_user", existingKeys.size());

        // ── Phase 3: 校验 + 分类 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "ROW_CHECK",
                "正在校验数据 (" + srcRows.size() + " 条)...");
        List<Map<String, Object>> toImport = new ArrayList<>();
        int existingCount = 0;
        int skippedCount = 0;
        List<String> skippedReasons = new ArrayList<>();

        // Dump first row keys + values to file
        if (!srcRows.isEmpty()) {
            var first = srcRows.get(0);
            StringBuilder sb = new StringBuilder();
            for (String k : first.keySet()) sb.append(k).append("=").append(first.get(k)).append("; ");
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/gas_row_dump.txt"), sb.toString()); } catch (Exception ign) {}
        }

        for (var row : srcRows) {
            String phone = strVal(row, "phone");
            String cert = strVal(row, "gas_supply_certificate_number");
            String communityName = strVal(row, "village_name");

            // 必填校验
            if (phone == null || phone.isBlank()) {
                log.warn("GasUserImport: skipping row — phone is empty, id={}", row.get("id"));
                skippedCount++;
                if (skippedReasons.size() < 20) skippedReasons.add("手机号为空 (id=" + row.get("id") + ")");
                continue;
            }
            if (cert == null || cert.isBlank()) {
                log.warn("GasUserImport: skipping row — certificate is empty, phone={}", phone);
                skippedCount++;
                if (skippedReasons.size() < 20) skippedReasons.add("供气证号为空 (phone=" + phone + ")");
                continue;
            }
            if (communityName == null || communityName.isBlank()) {
                log.warn("GasUserImport: skipping row — community_name is empty, phone={}", phone);
                skippedCount++;
                if (skippedReasons.size() < 20) skippedReasons.add("小区名为空 (phone=" + phone + ")");
                continue;
            }

            // 去重检查
            String key = phone + "|" + cert;
            if (existingKeys.contains(key)) {
                existingCount++;
                continue;
            }

            toImport.add(row);
        }
        log.warn("GasUserImport: company=[{}] rawTotal={} skipped={} existing={} toImport={}",
                company, srcRows.size(), skippedCount, existingCount, toImport.size());

        if (toImport.isEmpty()) {
            return buildResult(srcRows.size(), 0, existingCount, skippedCount);
        }

        // Community name → ID cache (avoids N+1 cross-db queries for same community)
        Map<String, Long> communityCache = new HashMap<>();

        // ── Phase 4: 逐条导入（查/建小区 → 插入用户） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "INSERT",
                "正在导入 " + toImport.size() + " 条新增用户...");

        int imported = 0;
        String insertSql = buildInsertSql();

        try (PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(insertSql)) {

            for (var row : toImport) {
                // 4a. 查找或创建小区（带缓存）
                String communityName = strVal(row, "village_name");
                Long communityId = communityCache.get(communityName);
                if (communityId == null) {
                    communityId = findOrCreateCommunity(row, corpId, communityName);
                    if (communityId != null) {
                        communityCache.put(communityName, communityId);
                    }
                }
                if (communityId == null) {
                    throw new RuntimeException("无法创建小区: " + communityName);
                }

                // 4b. 插入 ops_gas_user（JDBC batch）
                bindInsertParams(ps, row, corpId, communityId, batchNo);
                ps.addBatch();
                imported++;
            }

            ps.executeBatch();
        }

        return buildResult(srcRows.size(), imported, existingCount, skippedCount);
    }

    // ── 去重集合 ──

    private Set<String> loadExistingDedupKeys() {
        Set<String> keys = new HashSet<>();
        List<OpsGasUser> existing = gasUserMapper.selectList(
                new LambdaQueryWrapper<OpsGasUser>()
                        .isNotNull(OpsGasUser::getPhone)
                        .isNotNull(OpsGasUser::getGasSupplyCertificateNumber));
        for (OpsGasUser u : existing) {
            if (u.getPhone() != null && u.getGasSupplyCertificateNumber() != null) {
                keys.add(u.getPhone().trim() + "|" + u.getGasSupplyCertificateNumber().trim());
            }
        }
        return keys;
    }

    // ── 小区查找/创建 ──

    /**
     * 查找已有小区，不存在则创建（含地区匹配 + 高德地理编码）。
     */
    private Long findOrCreateCommunity(Map<String, Object> row, Long corpId, String communityName) {
        // 精确查已有小区
        Long existingId = findCommunityIdByName(communityName);
        if (existingId != null) return existingId;

        // 匹配地区
        Long regionId = matchRegion(row);
        if (regionId == null) {
            log.warn("GasUserImport: region match failed for community '{}'", communityName);
        }

        // 高德地理编码（优先用组织配置的 key）
        String amapKey = getOrgAmapKey(corpId);
        String address = buildGeocodeAddress(row, communityName);
        double[] coords = amapGeocodeUtil.geocode(address, amapKey);
        BigDecimal lng = coords != null ? BigDecimal.valueOf(coords[0]) : BigDecimal.ZERO;
        BigDecimal lat = coords != null ? BigDecimal.valueOf(coords[1]) : BigDecimal.ZERO;

        // 创建小区
        return createCommunity(communityName, regionId, lng, lat,
                strVal(row, "province_name"), strVal(row, "city_name"), strVal(row, "area_name"), address, corpId);
    }

    private Long findCommunityIdByName(String name) {
        try {
            return localJdbcTemplate.queryForObject(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name = ? AND delete_flag = 0 LIMIT 1",
                    Long.class, name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 三级匹配：区县 → 市 → 省。
     * 遵循 BattleMapController.climbToCounty() 的 sys_region 访问模式。
     */
    private Long matchRegion(Map<String, Object> row) {
        String province = strVal(row, "province_name");
        String city = strVal(row, "city_name");
        String district = strVal(row, "area_name");

        // Level 3: 区县
        if (district != null && !district.isBlank()) {
            Long id = findRegionByName(district, 3);
            if (id != null) return id;
        }
        // Level 2: 市
        if (city != null && !city.isBlank()) {
            Long id = findRegionByName(city, 2);
            if (id != null) return id;
        }
        // Level 1: 省
        if (province != null && !province.isBlank()) {
            Long id = findRegionByName(province, 1);
            if (id != null) return id;
        }
        return null;
    }

    private Long findRegionByName(String name, int level) {
        try {
            return localJdbcTemplate.queryForObject(
                    "SELECT id FROM ai_platform.sys_region WHERE region_name LIKE CONCAT('%',?,'%') AND region_level = ? LIMIT 1",
                    Long.class, name, level);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Long createCommunity(String name, Long regionId, BigDecimal lng, BigDecimal lat,
                                  String province, String city, String district, String address, Long corpId) {
        // 二次检查（避免并发创建）
        Long existing = findCommunityIdByName(name);
        if (existing != null) return existing;

        try {
            localJdbcTemplate.update(
                    "INSERT INTO ai_platform.sys_community " +
                    "(community_name, region_id, address, longitude, latitude, " +
                    " corp_id, delete_flag, create_time, modify_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                    name, regionId, address, lng, lat, corpId);

            Map<String, Object> newCommunity = localJdbcTemplate.queryForMap(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name = ? AND delete_flag = 0 ORDER BY id DESC LIMIT 1",
                    name);
            Long newId = newCommunity != null ? ((Number) newCommunity.get("id")).longValue() : null;
            log.warn("GasUserImport: created community id={} name={} regionId={} lng={} lat={}",
                    newId, name, regionId, lng, lat);
            return newId;
        } catch (Exception e) {
            // 可能是唯一约束冲突，再次查询
            log.warn("GasUserImport: createCommunity failed '{}': {}, retry query", name, e.getMessage());
            return findCommunityIdByName(name);
        }
    }

    // ── 地址拼接 ──

    private String buildGeocodeAddress(Map<String, Object> row, String communityName) {
        StringBuilder sb = new StringBuilder();
        String p = strVal(row, "province_name");
        String c = strVal(row, "city_name");
        String d = strVal(row, "area_name");
        if (!p.isEmpty()) sb.append(p);
        if (!c.isEmpty()) sb.append(c);
        if (!d.isEmpty()) sb.append(d);
        sb.append(communityName);
        return sb.toString();
    }

    // ── INSERT 构建 ──

    private String buildInsertSql() {
        return "INSERT INTO ops_gas_user " +
               "(corp_id, community_id, batch_no, user_no, meter_no, user_name, " +
               " phone, gas_supply_certificate_number, gas_company_name, area, address, " +
               " status, balance, arrears, open_time, create_user, modify_user, " +
               " create_user_name, modify_user_name, create_time, modify_time) " +
               "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,'','',NOW(),NOW())";
    }

    private void bindInsertParams(PreparedStatement ps, Map<String, Object> row,
                                   Long corpId, Long communityId, String batchNo) throws Exception {
        int i = 1;
        ps.setLong(i++, corpId);
        ps.setLong(i++, communityId);
        ps.setString(i++, batchNo);
        ps.setString(i++, strVal(row, "id"));                   // user_no
        ps.setString(i++, "");                                   // meter_no (not in Polardb)
        ps.setString(i++, strVal(row, "full_name"));             // user_name
        ps.setString(i++, strVal(row, "phone"));
        ps.setString(i++, strVal(row, "gas_supply_certificate_number"));
        ps.setString(i++, strVal(row, "gas_company_name"));
        ps.setString(i++, strVal(row, "area_name"));             // area
        ps.setString(i++, strVal(row, "detailed_address"));      // address
        ps.setString(i++, "active");
        ps.setBigDecimal(i++, toBigDecimal(row, "gas_balance")); // balance
        ps.setBigDecimal(i++, BigDecimal.ZERO);                  // arrears (not in Polardb)
        // openTime — parse once, then set at current index
        Object openTimeVal = null;
        String openTimeStr = strVal(row, "account_opening_time");
        if (openTimeStr != null && !openTimeStr.isBlank()) {
            try {
                openTimeVal = LocalDateTime.parse(openTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e1) {
                try {
                    openTimeVal = LocalDateTime.parse(openTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (Exception e2) {
                    try { openTimeVal = LocalDateTime.parse(openTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
                    catch (Exception e3) { /* leave as null */ }
                }
            }
        }
        ps.setObject(i++, openTimeVal);
    }

    // ── 结果构建 ──

    private ImportResult buildResult(int totalSrc, int imported, int existing, int skipped) {
        ImportResult r = ImportResult.success(imported, totalSrc);
        r.setExistingCount(existing);
        r.setFailRows(skipped);
        r.setMessage("外部共 " + totalSrc + " 条，已有用户 " + existing + "，增量导入 " + imported);
        return r;
    }

    /** 从平台组织 AI 配置中获取高德服务端 Key（解密后的）。 */
    private String getOrgAmapKey(Long corpId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/user/org-ai-config?corpId=" + corpId, (String) null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("amapServerKey") != null) {
                return data.get("amapServerKey").toString();
            }
        } catch (Exception e) {
            log.warn("GasUserImport: failed to get org amap key for corpId={}: {}", corpId, e.getMessage());
        }
        return null;
    }

    // ── 类型安全的辅助方法 ──

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private BigDecimal toBigDecimal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
