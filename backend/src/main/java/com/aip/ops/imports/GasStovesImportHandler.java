package com.aip.ops.imports;

import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.service.PlatformMerchantService;
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
 * 燃具信息异步增量同步处理器（Kafka async-import-task → task_type=gas_stoves）。
 *
 * 关联规则（2026-08-29 需求；需求字面的 s.gas_company_id 全表恒为 '181' 与 sys_corp 零匹配，
 *  实测公司标识在 gas_the_company_id，2026-08-27 亦已确认）：
 *   corp：sb_gas_stoves_info.gas_the_company_id → 平台 sys_corp.gas_company_id → sys_corp.id。
 *     未匹配 → corp_id=0 + corp_match_status=0（标记异常，不自动分配，等待人工处理）。
 *   gas_user_id 关联不变：ops_gas_user.gas_company_id = s.gas_the_company_id
 *     AND u.gas_supply_certificate_number = s.user_number → ops_gas_user.id。
 *
 * 增量规则（满足「不重复迁移」）：
 *   1. 本地已关联且组织已匹配（gas_user_id 非空、corp_match_status=1）→ 跳过，不做任何更新；
 *   2. 其余 → 匹配本地燃气用户 + 组织映射 → UPSERT（corp 仅来自组织映射，不再取燃气用户组织）。
 */
@Slf4j
@Component
public class GasStovesImportHandler implements ImportHandler {

    private final JdbcTemplate localJdbcTemplate;          // 本地 mall_db
    private final GasDataSourceConfig gasDataSourceConfig; // 外部 jinyuan_info
    private final DataSource dataSource;
    private final ImportTaskService taskService;
    private final PlatformMerchantService platformMerchantService; // 组织映射（gas_company_id → sys_corp.id）

    private static final int PAGE_SIZE = 500;
    private static final String SYNC_BATCH_PREFIX = "GAS_STOVES_SYNC_";

    /** 参与变更判定的同步字段（本地 SELECT 别名与外部列名一致，两侧共用同一哈希函数）。 */
    private static final List<String> HASH_FIELDS = List.of(
            "sb_gas_user_id", "t_gas_stoves_info_id", "gas_stoves_class", "gas_stoves_brand",
            "gas_stoves_type", "power", "focal_eye", "installation_time", "startup_time",
            "buy_time", "installation_position", "user_number", "gas_company_id",
            "gas_the_company_id", "user_id", "gas_stoves_factory", "gas_stoves_num");

    /** 单次同步的统计与失败原因聚合。 */
    private static class SyncStats {
        int inserted, updated, skippedLinked, linked, unmatchedTotal, corpUnmatchedTotal;
        final Map<String, Integer> unmatchedByOrg = new LinkedHashMap<>();
        final Map<String, Integer> corpUnmatchedByCompany = new LinkedHashMap<>();
    }

    public GasStovesImportHandler(
            JdbcTemplate localJdbcTemplate,
            GasDataSourceConfig gasDataSourceConfig,
            DataSource dataSource,
            ImportTaskService taskService,
            PlatformMerchantService platformMerchantService) {
        this.localJdbcTemplate = localJdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.dataSource = dataSource;
        this.taskService = taskService;
        this.platformMerchantService = platformMerchantService;
    }

    @Override
    public String getTaskType() {
        return "gas_stoves";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult execute(SysImportTask task) throws Exception {
        String batchNo = SYNC_BATCH_PREFIX + task.getId();

        // ── Phase 1: keyset 分页拉取外部燃具 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "READ_EXTERNAL",
                "正在从外部燃气系统读取燃具信息...");
        List<Map<String, Object>> srcRows = readExternalRows(task);
        if (srcRows.isEmpty()) {
            return ImportResult.fail("READ_EXTERNAL",
                    List.of("外部燃气系统中未找到燃具信息数据"), 0);
        }

        // ── Phase 2: 本地存量 + 燃气用户匹配索引 + 组织映射 ──
        taskService.updateStatus(task.getId(), "PROCESSING", "RESOLVE",
                "正在加载本地燃气用户并计算增量...");
        Map<String, Map<String, Object>> localRows = loadLocalRows();
        Map<String, Long> userIndex = loadUserMatchIndex();
        Map<String, Long> corpIndex = platformMerchantService.getCorpIdsByGasCompanyId();

        // ── Phase 3: 增量判定 + UPSERT（已关联且组织已匹配跳过，其余重算） ──
        taskService.updateStatus(task.getId(), "PROCESSING", "INSERT",
                "正在同步增量数据...");
        SyncStats stats = applyIncremental(srcRows, localRows, userIndex, corpIndex, batchNo);

        return buildResult(srcRows.size(), stats);
    }

    // ── Phase 3 实现：逐行增量判定（已关联跳过 / 匹配失败汇总） ──

    private SyncStats applyIncremental(List<Map<String, Object>> srcRows,
                                       Map<String, Map<String, Object>> localRows,
                                       Map<String, Long> userIndex,
                                       Map<String, Long> corpIndex,
                                       String batchNo) throws Exception {
        SyncStats st = new SyncStats();
        try (PreparedStatement ps = DataSourceUtils.getConnection(dataSource)
                .prepareStatement(buildUpsertSql())) {
            int batchCount = 0;
            for (var row : srcRows) {
                if (processRow(row, localRows, userIndex, corpIndex, batchNo, ps, st)) {
                    if (++batchCount % PAGE_SIZE == 0) ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return st;
    }

    /** 单行增量判定 + UPSERT；返回 true 表示已入批（需刷新批次计数）。 */
    private boolean processRow(Map<String, Object> row,
                               Map<String, Map<String, Object>> localRows,
                               Map<String, Long> userIndex,
                               Map<String, Long> corpIndex,
                               String batchNo, PreparedStatement ps, SyncStats st) throws Exception {
        Map<String, Object> local = localRows.get(strVal(row, "id"));
        // 已关联且组织已匹配 → 跳过（避免组织间数据搬家；组织未匹配的仍重算，可自愈）
        if (local != null && local.get("gas_user_id") != null
                && numVal(local.get("corp_match_status"), 0L) == 1) {
            st.skippedLinked++;
            return false;
        }

        Long gasUserId = userIndex.get(matchKey(row));
        if (gasUserId == null) recordUnmatched(st, row);

        long[] corp = resolveCorp(corpIndex, st, row);

        boolean changed = local == null
                || !Objects.equals(local.get("gas_user_id"), gasUserId)
                || !Objects.equals(numVal(local.get("corp_id"), null), corp[0])
                || !hashRow(local).equals(hashRow(row));
        if (!changed) return false;

        if (local == null) st.inserted++; else st.updated++;
        if (gasUserId != null) st.linked++;
        bindUpsertParams(ps, row, corp[0], (int) corp[1], batchNo, gasUserId);
        ps.addBatch();
        return true;
    }

    /** corp 仅来自组织映射：gas_the_company_id → sys_corp.gas_company_id → sys_corp.id；未命中 → [0,0] 异常标记。 */
    private long[] resolveCorp(Map<String, Long> corpIndex, SyncStats st, Map<String, Object> row) {
        String corpKey = trimToNull(strVal(row, "gas_the_company_id"));
        Long matchedCorp = corpKey != null ? corpIndex.get(corpKey) : null;
        if (matchedCorp == null) recordCorpUnmatched(st, row);
        return matchedCorp != null ? new long[]{matchedCorp, 1} : new long[]{0, 0};
    }

    /** 无法关联的原因按「组织ID|组织名」聚合，供结果提示与日志汇总。 */
    private void recordUnmatched(SyncStats st, Map<String, Object> row) {
        st.unmatchedTotal++;
        String cid = strVal(row, "gas_the_company_id");
        String cname = strVal(row, "company_name");
        st.unmatchedByOrg.merge((cid.isEmpty() ? "-" : cid) + "|" + (cname.isEmpty() ? "未知" : cname),
                1, Integer::sum);
    }

    /** 组织映射未命中（sys_corp 无该 gas_company_id）→ 按公司ID聚合，行将标记为异常（corp_id=0）。 */
    private void recordCorpUnmatched(SyncStats st, Map<String, Object> row) {
        st.corpUnmatchedTotal++;
        String cid = strVal(row, "gas_the_company_id");
        st.corpUnmatchedByCompany.merge(cid.isEmpty() ? "-" : cid, 1, Integer::sum);
    }

    /** 匹配键：公司ID + 供气证号（与 ops_gas_user 索引同构）。 */
    private String matchKey(Map<String, Object> row) {
        return strVal(row, "gas_the_company_id") + '' + strVal(row, "user_number");
    }

    // ── Phase 1 实现：keyset 分页全量拉取（varchar id 字典序，与 WHERE id > ? 同序保证不漏行） ──

    private List<Map<String, Object>> readExternalRows(SysImportTask task) {
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
                        "gas_stoves_factory, gas_stoves_num, company_name " +
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
            log.error("GasStovesImport: read external rows failed", e);
        }
        return srcRows;
    }

    // ── Phase 2 实现 ──

    /** 本地存量：id → 行（列别名与外部一致，供 hashRow 两侧共用）。 */
    private Map<String, Map<String, Object>> loadLocalRows() {
        Map<String, Map<String, Object>> map = new HashMap<>();
        try {
            localJdbcTemplate.query(
                    "SELECT id, sb_gas_user_id, t_gas_stoves_info_id, gas_stoves_class, " +
                    "gas_stoves_brand, gas_stoves_type, `power`, focal_eye, " +
                    "installation_time, startup_time, buy_time, installation_position, " +
                    "user_number, gas_company_id, gas_the_company_id, user_id, " +
                    "gas_stoves_factory, gas_stoves_num, gas_user_id, corp_id, corp_match_status " +
                    "FROM ops_gas_stoves_info WHERE delete_flag = 0",
                    rs -> {
                        Map<String, Object> row = new HashMap<>();
                        for (String f : HASH_FIELDS) row.put(f, rs.getObject(f));
                        // getLong 对 NULL 返回 0，必须用 getObject 保持可空语义
                        row.put("gas_user_id", nullableLong(rs.getObject("gas_user_id")));
                        row.put("corp_id", rs.getLong("corp_id"));
                        row.put("corp_match_status", rs.getInt("corp_match_status"));
                        String id = rs.getString("id");
                        if (id != null) map.put(id.trim(), row);
                    });
        } catch (Exception e) {
            // 表/列不存在（DDL 未执行）时降级为全量新增，由主键兜底
            log.warn("GasStovesImport: load local rows failed: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 本地燃气用户匹配索引：公司ID+证号 → 用户ID（同键多用户取 MIN(id) 确定性取值）。
     * corp 不再从燃气用户获取，改由组织映射（gas_company_id → sys_corp.id）解析。
     */
    private Map<String, Long> loadUserMatchIndex() {
        Map<String, Long> index = new HashMap<>();
        try {
            localJdbcTemplate.query(
                    "SELECT id, gas_company_id, gas_supply_certificate_number " +
                    "FROM ops_gas_user WHERE delete_flag = 0 " +
                    "AND gas_company_id IS NOT NULL AND gas_company_id <> '' " +
                    "AND gas_supply_certificate_number IS NOT NULL AND gas_supply_certificate_number <> ''",
                    rs -> {
                        long uid = rs.getLong(1);
                        String cid = trimToNull(rs.getString(2));
                        String cert = trimToNull(rs.getString(3));
                        if (cid == null || cert == null) return;
                        String key = cid + '' + cert;
                        Long cur = index.get(key);
                        if (cur == null || uid < cur) index.put(key, uid);
                    });
        } catch (Exception e) {
            // 无 gas_company_id 列（DDL 未执行）时索引为空，全部按无法关联处理
            log.warn("GasStovesImport: load user match index failed: {}", e.getMessage());
        }
        return index;
    }

    // ── UPSERT SQL（gas_user_id 关联；重复时全字段覆盖为最新值） ──

    private String buildUpsertSql() {
        return "INSERT INTO ops_gas_stoves_info " +
               "(id, corp_id, corp_match_status, gas_user_id, batch_no, sb_gas_user_id, t_gas_stoves_info_id, " +
               " gas_stoves_class, gas_stoves_brand, gas_stoves_type, `power`, focal_eye, " +
               " installation_time, startup_time, buy_time, installation_position, user_number, " +
               " gas_company_id, gas_the_company_id, user_id, gas_stoves_factory, gas_stoves_num, " +
               " create_time, modify_time) " +
               "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
               "ON DUPLICATE KEY UPDATE " +
               " gas_user_id=VALUES(gas_user_id), corp_id=VALUES(corp_id), " +
               " corp_match_status=VALUES(corp_match_status), " +
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
                                  long corpId, int matchStatus, String batchNo, Long gasUserId) throws Exception {
        int i = 1;
        ps.setString(i++, strVal(row, "id"));                    // 主键 = 远程 id
        ps.setLong(i++, corpId);
        ps.setInt(i++, matchStatus);                             // 组织匹配状态（0=未匹配异常 1=已匹配）
        setLongOrNull(ps, i++, gasUserId);                       // 关联燃气用户（未匹配为 NULL）
        ps.setString(i++, batchNo);
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

    // ── 变更判定哈希（两侧行结构一致：外部/本地均以同名字段 + 统一类型归一化） ──

    private String hashRow(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder(256);
        for (String k : HASH_FIELDS) {
            Object v = row.get(k);
            if (k.endsWith("_time")) {
                LocalDateTime t = parseDateTime(v);
                sb.append(t == null ? "" : t.toString());
            } else {
                sb.append(v == null ? "" : v.toString().trim());
            }
            sb.append('');
        }
        return sb.toString();
    }

    // ── 结果构建（含无法关联原因汇总） ──

    private ImportResult buildResult(int totalSrc, SyncStats st) {
        ImportResult r = ImportResult.success(st.inserted + st.updated, totalSrc);
        r.setExistingCount(st.skippedLinked);
        StringBuilder msg = new StringBuilder("外部共 " + totalSrc + " 条，新增 " + st.inserted
                + "，更新 " + st.updated + "，跳过已关联 " + st.skippedLinked
                + "，本次新关联 " + st.linked + " 台");
        if (st.unmatchedTotal > 0) {
            msg.append("；无法关联 ").append(st.unmatchedTotal).append(" 条：");
            int shown = 0;
            for (var e : st.unmatchedByOrg.entrySet()) {
                if (shown++ == 5) {
                    msg.append("等 ").append(st.unmatchedByOrg.size()).append(" 个组织");
                    break;
                }
                String[] parts = e.getKey().split("\\|", 2);
                msg.append("组织 ").append(parts[0]).append(" 的 ").append(parts[1])
                        .append(" 的用户数据无法关联，请检查（").append(e.getValue()).append(" 条）；");
            }
            log.warn("GasStovesImport: {} unmatched stoves by org: {}", st.unmatchedTotal, st.unmatchedByOrg);
        }
        if (st.corpUnmatchedTotal > 0) {
            msg.append("；组织未匹配（已标记异常 corp_id=0，待人工处理）").append(st.corpUnmatchedTotal).append(" 条：公司ID ");
            msg.append(String.join(",", st.corpUnmatchedByCompany.keySet()));
            log.warn("GasStovesImport: {} corp-unmatched stoves: {}", st.corpUnmatchedTotal, st.corpUnmatchedByCompany);
        }
        r.setMessage(msg.toString());
        return r;
    }

    // ── 类型安全的辅助方法 ──

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private Long nullableLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private Integer intVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private Long numVal(Object v, Long def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(v.toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    private void setLongOrNull(PreparedStatement ps, int idx, Long v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.BIGINT);
        else ps.setLong(idx, v);
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
