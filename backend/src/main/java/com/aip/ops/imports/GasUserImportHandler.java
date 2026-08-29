package com.aip.ops.imports;

import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.OpsGasUser;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsGasUserMapper;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.service.PlatformMerchantService;
import com.aip.ops.util.AesUtils;
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
 * 以 user_no（= sb_gas_user.id）去重后增量写入 ops_gas_user。
 * 自动创建缺失小区（含高德地理编码 + 地区匹配）。
 * 采用事务内 JDBC batch INSERT，任一步失败全部回滚。
 *
 * corp_id 逐行解析（2026-08-29 重构）：sb_gas_user.gas_company_id → sys_corp.gas_company_id → sys_corp.id，
 * 不再取任务发起组织；未匹配 → corp_id=0 + corp_match_status=0 异常标记（不猜测、不沿用其他字段）。
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
    private final PlatformMerchantService platformMerchantService;
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
            PlatformMerchantService platformMerchantService,
            @Qualifier("gasCompanyFilter") String companyFilter) {
        this.gasUserMapper = gasUserMapper;
        this.localJdbcTemplate = localJdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.dataSource = dataSource;
        this.amapGeocodeUtil = amapGeocodeUtil;
        this.taskService = taskService;
        this.platformInternalClient = platformInternalClient;
        this.platformMerchantService = platformMerchantService;
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
        // 组织未匹配统计：gas_company_id → 条数（供结果消息与异常日志，后续人工修正）
        Map<String, Integer> corpUnmatched = new LinkedHashMap<>();

        for (var row : srcRows) {
            String phone = strVal(row, "phone");
            String cert = strVal(row, "gas_supply_certificate_number");
            String communityName = strVal(row, "village_name");
            if (phone == null || phone.isBlank()) { skippedCount++; continue; }
            // 源数据 phone 可能多号码拼接（如 "15009597943、15121945317"）或超长，规整后再入库
            String normalizedPhone = normalizePhone(phone);
            if (!normalizedPhone.equals(phone)) row.put("phone", normalizedPhone);
            if (cert == null || cert.isBlank()) { skippedCount++; continue; }
            if (communityName == null || communityName.isBlank()) { skippedCount++; continue; }
            // 去重：user_no = sb_gas_user.id
            if (existingKeys.contains(strVal(row, "id"))) { existingCount++; continue; }
            toImport.add(row);
        }
        log.warn("GasUserImport: company=[{}] rawTotal={} skipped={} existing={} toImport={}",
                company, srcRows.size(), skippedCount, existingCount, toImport.size());

        if (toImport.isEmpty()) {
            return buildResult(srcRows.size(), 0, existingCount, skippedCount, corpUnmatched);
        }

        // 组织映射：外部 gas_company_id → sys_corp.id（迁移前一次性拉取；平台不可达时为空 → 全部标记未匹配，fail-safe）
        Map<String, Long> corpIndex = platformMerchantService.getCorpIdsByGasCompanyId();

        // Community name → ID cache (avoids N+1 cross-db queries for same community)
        Map<String, Long> communityCache = new HashMap<>();

        // ── Phase 4: 逐条导入（解析组织 → 查/建小区 → 插入用户） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "INSERT",
                "正在导入 " + toImport.size() + " 条新增用户...");

        int imported = 0;
        String insertSql = buildInsertSql();

        try (PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(insertSql)) {

            for (var row : toImport) {
                // 4a. 逐行解析组织（corp_id 来源唯一：gas_company_id → sys_corp 匹配）
                long[] corp = resolveCorp(row, corpIndex, corpUnmatched);
                long rowCorpId = corp[0];
                int matchStatus = (int) corp[1];

                // 4b. 查找或创建小区（带缓存；未匹配行回退任务组织，避免小区归属 corp 0）
                String communityName = strVal(row, "village_name");
                Long communityId = communityCache.get(communityName);
                if (communityId == null) {
                    communityId = findOrCreateCommunity(row, rowCorpId > 0 ? rowCorpId : corpId, communityName);
                    if (communityId != null) {
                        communityCache.put(communityName, communityId);
                    }
                }
                if (communityId == null) {
                    throw new RuntimeException("无法创建小区: " + communityName);
                }

                // 4c. 插入 ops_gas_user（JDBC batch）
                bindInsertParams(ps, row, rowCorpId, matchStatus, communityId, batchNo);
                ps.addBatch();
                imported++;
            }

            ps.executeBatch();
        }

        return buildResult(srcRows.size(), imported, existingCount, skippedCount, corpUnmatched);
    }

    /** 逐行解析组织：sb_gas_user.gas_company_id → sys_corp.gas_company_id → sys_corp.id。
     *  未匹配 → corp_id=0 + 状态0（异常标记），不猜测、不沿用任务组织或其他字段。 */
    private long[] resolveCorp(Map<String, Object> row, Map<String, Long> corpIndex,
                               Map<String, Integer> corpUnmatched) {
        String companyKey = strValOrNull(row, "gas_company_id");
        Long matched = companyKey != null ? corpIndex.get(companyKey) : null;
        if (matched == null) {
            String key = companyKey != null ? companyKey : "(空)";
            // 每公司仅记前 3 条明细日志防刷屏，全量计数进结果消息
            int n = corpUnmatched.merge(key, 1, Integer::sum);
            if (n <= 3) {
                log.warn("GasUserImport: 组织未匹配 gas_company_id=[{}] user_no=[{}] 姓名=[{}]，corp_id 置 0 待人工处理",
                        key, strVal(row, "id"), strVal(row, "full_name"));
            }
            return new long[]{0L, 0L};
        }
        return new long[]{matched, 1L};
    }

    /** 规整源表 phone：多号码拼接时取首个合法 11 位手机号；
     *  否则按常见分隔符拆段取首段，并兜底截断到 20 字符（ops_gas_user.phone varchar(20)），
     *  保证入库不报 Data truncation。正常 11 位手机号原样保留。 */
    private String normalizePhone(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;
        if (s.matches("1\\d{10}")) return s;
        for (String part : s.split("[、，,;；/|\\s]+")) {
            if (part.matches("1\\d{10}")) return part;
        }
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    // ── 去重集合（按 user_no = sb_gas_user.id） ──

    private Set<String> loadExistingDedupKeys() {
        Set<String> keys = new HashSet<>();
        List<OpsGasUser> existing = gasUserMapper.selectList(
                new LambdaQueryWrapper<OpsGasUser>()
                        .isNotNull(OpsGasUser::getUserNo));
        for (OpsGasUser u : existing) {
            if (u.getUserNo() != null) {
                keys.add(u.getUserNo().trim());
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

        // 地理编码：调用底座平台 API（使用组织配置的 AMap Key）
        String address = buildGeocodeAddress(row, communityName);
        double[] coords = geocodeViaPlatform(corpId, address);
        if (coords == null) {
            log.warn("GasUserImport: geocode failed for '{}', using (0,0)", address);
            coords = new double[]{0.0, 0.0};
        }
        BigDecimal lng = BigDecimal.valueOf(coords[0]);
        BigDecimal lat = BigDecimal.valueOf(coords[1]);

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
               "(corp_id, corp_match_status, community_id, batch_no, user_no, meter_no, full_name, " +
               " gender, birth_date, " +
               " phone, gas_supply_certificate_number, gas_company_name, gas_company_id, area_name, detailed_address, " +
               " status, gas_balance, arrears, account_opening_time, " +
               " accumulated_gas_purchase_volume, accumulated_gas_purchase_money, " +
               " document_type, document_number, create_user, modify_user, " +
               " create_user_name, modify_user_name, create_time, modify_time) " +
               "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,'','',NOW(),NOW())";
    }

    private void bindInsertParams(PreparedStatement ps, Map<String, Object> row,
                                   Long corpId, int matchStatus, Long communityId, String batchNo) throws Exception {
        int i = 1;
        ps.setLong(i++, corpId);
        ps.setInt(i++, matchStatus);       // corp_match_status
        ps.setLong(i++, communityId);
        ps.setString(i++, batchNo);
        ps.setString(i++, strVal(row, "id"));                   // user_no
        ps.setString(i++, "");                                   // meter_no
        ps.setString(i++, strVal(row, "full_name"));             // full_name
        // 身份证解析：性别 + 出生日期
        String[] idInfo = parseIdCard(strVal(row, "document_number"));
        ps.setString(i++, idInfo[0]);  // gender
        ps.setObject(i++, idInfo[1] != null ? java.sql.Date.valueOf(idInfo[1]) : null); // birth_date (nullable)
        ps.setString(i++, strVal(row, "phone"));
        ps.setString(i++, strVal(row, "gas_supply_certificate_number"));
        ps.setString(i++, strVal(row, "gas_company_name"));
        ps.setString(i++, strValOrNull(row, "gas_company_id"));  // 空值存 NULL，避免未来关联匹配 ''='' 假阳性
        ps.setString(i++, strVal(row, "area_name"));             // area_name (matches ops_gas_user)
        ps.setString(i++, strVal(row, "detailed_address"));      // detailed_address (matches ops_gas_user)
        ps.setString(i++, "active");
        ps.setBigDecimal(i++, toBigDecimal(row, "gas_balance")); // gas_balance (matches ops_gas_user)
        ps.setBigDecimal(i++, BigDecimal.ZERO);                  // arrears
        ps.setObject(i++, parseOpenTime(strVal(row, "account_opening_time")));
        // sb_gas_user 额外字段（列名一致，直接映射）
        ps.setBigDecimal(i++, toBigDecimal(row, "accumulated_gas_purchase_volume"));
        ps.setBigDecimal(i++, toBigDecimal(row, "accumulated_gas_purchase_money"));
        ps.setString(i++, strVal(row, "document_type"));
        ps.setString(i++, strVal(row, "document_number"));
    }

    // ── 结果构建 ──

    private ImportResult buildResult(int totalSrc, int imported, int existing, int skipped,
                                     Map<String, Integer> corpUnmatched) {
        ImportResult r = ImportResult.success(imported, totalSrc);
        r.setExistingCount(existing);
        r.setFailRows(skipped);
        StringBuilder msg = new StringBuilder("外部共 ").append(totalSrc)
                .append(" 条，已有用户 ").append(existing).append("，增量导入 ").append(imported);
        if (!corpUnmatched.isEmpty()) {
            msg.append("；组织未匹配（corp_id=0 已标记异常，待人工处理）");
            corpUnmatched.forEach((k, v) -> msg.append(" ").append(k).append(":").append(v).append(" 条"));
        }
        r.setMessage(msg.toString());
        return r;
    }

    /** 调用底座平台地理编码 API（使用组织配置的 AMap Key）。返回 [lng, lat] 或 null。 */
    private double[] geocodeViaPlatform(Long corpId, String address) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Internal-Token", "aip-internal-svc-token-2026");
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
            String url = "http://localhost:8080/api/internal/user/geocode?corpId={corpId}&address={address}";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = new org.springframework.web.client.RestTemplate()
                    .exchange(url, org.springframework.http.HttpMethod.GET, entity, Map.class, corpId, address)
                    .getBody();
            if (resp != null && resp.get("data") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data.get("longitude") != null && data.get("latitude") != null) {
                    double lng = ((Number) data.get("longitude")).doubleValue();
                    double lat = ((Number) data.get("latitude")).doubleValue();
                    return new double[]{lng, lat};
                }
            }
            log.warn("GasUserImport: platform geocode returned no coords for '{}'", address);
        } catch (Exception e) {
            log.warn("GasUserImport: platform geocode failed for '{}': {}", address, e.getMessage());
        }
        return null;
    }

    /** 从身份证号解析性别和出生日期。返回 [gender, birthDate] — gender: "1"/"0"/null(=未知), birthDate: "yyyy-MM-dd"/null */
    private String[] parseIdCard(String idCard) {
        String gender = null, birth = null;
        if (idCard == null || idCard.isBlank()) return new String[]{null, null};
        String s = idCard.trim();
        try {
            if (s.length() == 18 && s.matches("\\d{17}[\\dXx]")) {
                birth = s.substring(6, 10) + "-" + s.substring(10, 12) + "-" + s.substring(12, 14);
                java.sql.Date.valueOf(birth);
                int g = Integer.parseInt(s.substring(16, 17));
                gender = (g % 2 == 1) ? "1" : "0";  // 1=男 0=女
            } else if (s.length() == 15 && s.matches("\\d{15}")) {
                birth = "19" + s.substring(6, 8) + "-" + s.substring(8, 10) + "-" + s.substring(10, 12);
                java.sql.Date.valueOf(birth);
                int g = Integer.parseInt(s.substring(14, 15));
                gender = (g % 2 == 1) ? "1" : "0";
            } else {
                return new String[]{null, null}; // not a valid ID card
            }
        } catch (Exception e) {
            return new String[]{null, null};
        }
        return new String[]{gender, birth};
    }

    // ── 类型安全的辅助方法 ──

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private String strValOrNull(Map<String, Object> row, String key) {
        String s = strVal(row, key);
        return s.isEmpty() ? null : s;
    }

    /** 开户时间多格式解析（yyyy-MM-dd HH:mm:ss / yyyy-MM-dd / ISO），失败返回 null。 */
    private LocalDateTime parseOpenTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (Exception e1) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
        catch (Exception e2) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (Exception e3) { return null; }
    }

    private BigDecimal toBigDecimal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
