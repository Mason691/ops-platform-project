package com.aip.ops.imports;

import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.ImportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 燃具信息异步同步处理器。
 * 全量拉取外部燃气数据库 jinyuan_info.sb_gas_stoves_info，
 * 以 remote_id（= sb_gas_stoves_info.id）为唯一键 UPSERT 到 ops_gas_stoves_info。
 * 纯镜像迁移：无小区/地理编码逻辑，user_number 直接取远程行。
 */
@Slf4j
@Component
public class GasStovesImportHandler implements ImportHandler {

    private final JdbcTemplate localJdbcTemplate;        // 本地 mall_db
    private final GasDataSourceConfig gasDataSourceConfig; // 外部 jinyuan_info
    private final DataSource dataSource;
    private final ImportTaskService taskService;

    private static final int PAGE_SIZE = 500;
    private static final String SYNC_BATCH_PREFIX = "GAS_STOVES_SYNC_";

    public GasStovesImportHandler(
            JdbcTemplate localJdbcTemplate,
            GasDataSourceConfig gasDataSourceConfig,
            DataSource dataSource,
            ImportTaskService taskService) {
        this.localJdbcTemplate = localJdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.dataSource = dataSource;
        this.taskService = taskService;
    }

    @Override
    public String getTaskType() {
        return "gas_stoves";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult execute(SysImportTask task) throws Exception {
        Long corpId = task.getCorpId();
        String batchNo = SYNC_BATCH_PREFIX + task.getId();

        // ── Phase 1: keyset 分页全量拉取（varchar id 字典序，与 WHERE id > ? 同序保证不漏行） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "READ_EXTERNAL",
                "正在从外部燃气系统读取燃具信息...");
        JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
        List<Map<String, Object>> srcRows = new ArrayList<>();
        String afterId = "";
        try {
            while (true) {
                List<Map<String, Object>> page = gasJdbc.queryForList(
                        "SELECT id, sb_gas_user_id, t_gas_stoves_info_id, gas_stoves_class, " +
                        "gas_stoves_brand, gas_stoves_type, `power`, focal_eye, " +
                        "installation_time, startup_time, buy_time, installation_position, " +
                        "user_number, gas_company_id, gas_the_company_id, user_id, " +
                        "gas_stoves_factory, gas_stoves_num " +
                        "FROM sb_gas_stoves_info WHERE id > ? ORDER BY id ASC LIMIT " + PAGE_SIZE,
                        afterId);
                if (page.isEmpty()) break;
                srcRows.addAll(page);
                afterId = strVal(page.get(page.size() - 1), "id");
                if (srcRows.size() % (PAGE_SIZE * 5) == 0) {
                    taskService.updateStatus(task.getId(), "PROCESSING", "READ_EXTERNAL",
                            "正在读取外部燃具信息... 已读取 " + srcRows.size() + " 条");
                }
                if (page.size() < PAGE_SIZE) break;
            }
        } catch (Exception e) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("无法连接外部燃气数据库: " + e.getMessage()), 0);
        }
        if (srcRows.isEmpty()) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("外部燃气系统中未找到燃具信息数据"), 0);
        }
        log.warn("GasStovesImport: totalRows={}", srcRows.size());

        // ── Phase 2: 现有 remote_id 集合（预判新增/更新，供结果统计） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "DEDUP",
                "正在查询已有燃具信息...");
        Set<String> existingIds = loadExistingRemoteIds();
        int inserted = 0, updated = 0;
        for (var row : srcRows) {
            if (existingIds.contains(strVal(row, "id"))) updated++;
            else inserted++;
        }

        // ── Phase 3: UPSERT batch（500 条一批冲刷，防大表内存压力） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "INSERT",
                "正在同步 " + srcRows.size() + " 条燃具信息...");
        try (PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(buildUpsertSql())) {
            int batchCount = 0;
            for (var row : srcRows) {
                bindUpsertParams(ps, row, corpId, batchNo);
                ps.addBatch();
                if (++batchCount % PAGE_SIZE == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }

        return buildResult(srcRows.size(), inserted, updated);
    }

    private Set<String> loadExistingRemoteIds() {
        Set<String> ids = new HashSet<>();
        try {
            localJdbcTemplate.query(
                    "SELECT remote_id FROM ops_gas_stoves_info WHERE delete_flag = 0",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.add(rs.getString(1).trim()));
        } catch (Exception e) {
            // 表不存在（未执行 DDL）时降级为全量插入，由唯一键兜底
            log.warn("GasStovesImport: load existing remote ids failed: {}", e.getMessage());
        }
        return ids;
    }

    // ── UPSERT SQL ──
    // 重复时不更新 corp_id/batch_no/create_time/delete_flag：
    // 首个同步组织保持归属；逻辑删除行不被复活（delete_flag 保持 1，查询不可见）。
    private String buildUpsertSql() {
        return "INSERT INTO ops_gas_stoves_info " +
               "(corp_id, batch_no, remote_id, sb_gas_user_id, t_gas_stoves_info_id, " +
               " gas_stoves_class, gas_stoves_brand, gas_stoves_type, `power`, focal_eye, " +
               " installation_time, startup_time, buy_time, installation_position, user_number, " +
               " gas_company_id, gas_the_company_id, user_id, gas_stoves_factory, gas_stoves_num, " +
               " create_time, modify_time) " +
               "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
               "ON DUPLICATE KEY UPDATE " +
               " sb_gas_user_id=VALUES(sb_gas_user_id), t_gas_stoves_info_id=VALUES(t_gas_stoves_info_id), " +
               " gas_stoves_class=VALUES(gas_stoves_class), gas_stoves_brand=VALUES(gas_stoves_brand), " +
               " gas_stoves_type=VALUES(gas_stoves_type), `power`=VALUES(`power`), " +
               " focal_eye=VALUES(focal_eye), installation_time=VALUES(installation_time), " +
               " startup_time=VALUES(startup_time), buy_time=VALUES(buy_time), " +
               " installation_position=VALUES(installation_position), user_number=VALUES(user_number), " +
               " gas_company_id=VALUES(gas_company_id), gas_the_company_id=VALUES(gas_the_company_id), " +
               " user_id=VALUES(user_id), gas_stoves_factory=VALUES(gas_stoves_factory), " +
               " gas_stoves_num=VALUES(gas_stoves_num), modify_time=NOW()";
    }

    private void bindUpsertParams(PreparedStatement ps, Map<String, Object> row,
                                  Long corpId, String batchNo) throws Exception {
        int i = 1;
        ps.setLong(i++, corpId);
        ps.setString(i++, batchNo);
        ps.setString(i++, strVal(row, "id"));                    // remote_id
        ps.setString(i++, strVal(row, "sb_gas_user_id"));
        ps.setString(i++, strVal(row, "t_gas_stoves_info_id"));
        ps.setString(i++, strVal(row, "gas_stoves_class"));
        ps.setString(i++, strVal(row, "gas_stoves_brand"));
        ps.setString(i++, strVal(row, "gas_stoves_type"));
        setIntOrNull(ps, i++, intVal(row, "power"));
        ps.setString(i++, strVal(row, "focal_eye"));
        setDateTime(ps, i++, parseDateTime(row.get("installation_time")));
        setDateTime(ps, i++, parseDateTime(row.get("startup_time")));
        setDateTime(ps, i++, parseDateTime(row.get("buy_time")));
        ps.setString(i++, strVal(row, "installation_position"));
        ps.setString(i++, strVal(row, "user_number"));
        ps.setString(i++, strVal(row, "gas_company_id"));
        ps.setString(i++, strVal(row, "gas_the_company_id"));
        ps.setString(i++, strVal(row, "user_id"));
        ps.setString(i++, strVal(row, "gas_stoves_factory"));
        setIntOrNull(ps, i++, intVal(row, "gas_stoves_num"));
    }

    // ── 结果构建 ──

    private ImportResult buildResult(int totalSrc, int inserted, int updated) {
        ImportResult r = ImportResult.success(inserted + updated, totalSrc);
        r.setExistingCount(updated);
        r.setMessage("外部共 " + totalSrc + " 条，新增 " + inserted + "，更新 " + updated);
        return r;
    }

    // ── 类型安全的辅助方法 ──

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private Integer intVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else ps.setInt(idx, v);
    }

    private void setDateTime(PreparedStatement ps, int idx, LocalDateTime v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.TIMESTAMP);
        else ps.setObject(idx, v);
    }

    /** 兼容 JDBC 返回的 LocalDateTime/Timestamp 及字符串三种形态；解析失败返回 null 不中断整批。 */
    private LocalDateTime parseDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay(); }
        catch (Exception ignored) {}
        try { return LocalDateTime.parse(s.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (Exception ignored) {}
        return null;
    }
}
