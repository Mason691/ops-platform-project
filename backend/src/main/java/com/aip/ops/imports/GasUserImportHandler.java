package com.aip.ops.imports;

import com.aip.ops.entity.OpsGasUser;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsGasUserMapper;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.util.AmapGeocodeUtil;
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
    private final JdbcTemplate gasJdbcTemplate;   // 外部 jinyuan_info
    private final JdbcTemplate localJdbcTemplate; // 本地 mall_db（含 ai_platform 跨库）
    private final DataSource dataSource;
    private final AmapGeocodeUtil amapGeocodeUtil;
    private final ImportTaskService taskService;

    private static final String IMPORT_BATCH_PREFIX = "GAS_IMP_";

    public GasUserImportHandler(
            OpsGasUserMapper gasUserMapper,
            @Qualifier("gasJdbcTemplate") JdbcTemplate gasJdbcTemplate,
            JdbcTemplate localJdbcTemplate,
            DataSource dataSource,
            AmapGeocodeUtil amapGeocodeUtil,
            ImportTaskService taskService) {
        this.gasUserMapper = gasUserMapper;
        this.gasJdbcTemplate = gasJdbcTemplate;
        this.localJdbcTemplate = localJdbcTemplate;
        this.dataSource = dataSource;
        this.amapGeocodeUtil = amapGeocodeUtil;
        this.taskService = taskService;
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
        List<Map<String, Object>> srcRows;
        try {
            srcRows = gasJdbcTemplate.queryForList(
                    "SELECT * FROM sb_gas_user WHERE gas_company_name LIKE '德保%'");
        } catch (Exception e) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("无法连接外部燃气数据库: " + e.getMessage()), 0);
        }
        if (srcRows.isEmpty()) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("外部燃气系统中未找到符合条件的用户数据"), 0);
        }
        log.info("GasUserImport: read {} rows from external DB", srcRows.size());

        // ── Phase 2: 查询现有去重集合 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "DEDUP",
                "正在查询已有用户，构建去重集合...");
        Set<String> existingKeys = loadExistingDedupKeys();
        log.info("GasUserImport: {} existing dedup keys in ops_gas_user", existingKeys.size());

        // ── Phase 3: 校验 + 分类 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "ROW_CHECK",
                "正在校验数据 (" + srcRows.size() + " 条)...");
        List<Map<String, Object>> toImport = new ArrayList<>();
        int existingCount = 0;
        int skippedCount = 0;
        List<String> skippedReasons = new ArrayList<>();

        for (var row : srcRows) {
            String phone = strVal(row, "phone");
            String cert = strVal(row, "gas_supply_certificate_number");
            String communityName = strVal(row, "community_name");

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
        log.info("GasUserImport: skipped={} existing={} toImport={}",
                skippedCount, existingCount, toImport.size());

        if (toImport.isEmpty()) {
            return buildResult(srcRows.size(), 0, existingCount, skippedCount);
        }

        // ── Phase 4: 逐条导入（查/建小区 → 插入用户） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "INSERT",
                "正在导入 " + toImport.size() + " 条新增用户...");

        int imported = 0;
        String insertSql = buildInsertSql();

        try (PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(insertSql)) {

            for (var row : toImport) {
                // 4a. 查找或创建小区
                String communityName = strVal(row, "community_name");
                Long communityId = findOrCreateCommunity(row, corpId, communityName);
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

        // 高德地理编码
        String address = buildGeocodeAddress(row, communityName);
        double[] coords = amapGeocodeUtil.geocode(address);
        BigDecimal lng = coords != null ? BigDecimal.valueOf(coords[0]) : BigDecimal.ZERO;
        BigDecimal lat = coords != null ? BigDecimal.valueOf(coords[1]) : BigDecimal.ZERO;

        // 创建小区
        return createCommunity(communityName, regionId, lng, lat,
                strVal(row, "province"), strVal(row, "city"), strVal(row, "district"), address, corpId);
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
        String province = strVal(row, "province");
        String city = strVal(row, "city");
        String district = strVal(row, "district");

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
            String safeName = name.replace("'", "''");
            return localJdbcTemplate.queryForObject(
                    "SELECT id FROM ai_platform.sys_region WHERE region_name LIKE '%"
                            + safeName + "%' AND region_level = " + level + " LIMIT 1",
                    Long.class);
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
                    "(community_name, province, city, district, address, longitude, latitude, " +
                    " region_id, corp_id, status, delete_flag, create_time, modify_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, NOW(), NOW())",
                    name, province, city, district, address, lng, lat,
                    regionId, corpId);

            Map<String, Object> newCommunity = localJdbcTemplate.queryForMap(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name = ? AND delete_flag = 0 ORDER BY id DESC LIMIT 1",
                    name);
            Long newId = newCommunity != null ? ((Number) newCommunity.get("id")).longValue() : null;
            log.info("GasUserImport: created community id={} name={} regionId={} lng={} lat={}",
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
        return strVal(row, "province") + strVal(row, "city") + strVal(row, "district") + communityName;
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
        ps.setString(i++, strVal(row, "user_no"));
        ps.setString(i++, strVal(row, "meter_no"));
        ps.setString(i++, strVal(row, "user_name"));
        ps.setString(i++, strVal(row, "phone"));
        ps.setString(i++, strVal(row, "gas_supply_certificate_number"));
        ps.setString(i++, strVal(row, "gas_company_name"));
        ps.setString(i++, strVal(row, "area"));
        ps.setString(i++, strVal(row, "address"));
        ps.setString(i++, "active");
        ps.setBigDecimal(i++, toBigDecimal(row, "balance"));
        ps.setBigDecimal(i++, toBigDecimal(row, "arrears"));
        // openTime: try to parse, else null
        String openTimeStr = strVal(row, "open_time");
        if (openTimeStr != null && !openTimeStr.isBlank()) {
            try {
                ps.setObject(i++, LocalDateTime.parse(openTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                try {
                    ps.setObject(i++, LocalDateTime.parse(openTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                } catch (Exception e2) {
                    ps.setObject(i++, null);
                }
            }
        } else {
            ps.setObject(i++, null);
        }
    }

    // ── 结果构建 ──

    private ImportResult buildResult(int totalSrc, int imported, int existing, int skipped) {
        ImportResult r = ImportResult.success(imported, totalSrc);
        r.setExistingCount(existing);
        r.setFailRows(skipped);
        r.setMessage("已有用户 " + existing + "，增量导入 " + imported);
        return r;
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
