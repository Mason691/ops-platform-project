package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsMemberLedger;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsMemberLedgerMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 会员台账 — 按平台会员聚合展示（重构后）。
 * 列表每行一个 ai_platform.sys_member（仅含至少关联一台设备的会员），
 * 设备明细（安检采集/销售业务两组）由 {@code GET /member/{platformMemberId}/devices} 提供；
 * 与活动信息彻底解耦（列表无任何活动字段）。
 * create/update/delete 保留原语义——主站复盘页销售台账编辑器直写本表（含 activityId）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/member-ledger")
@RequiredArgsConstructor
public class MemberLedgerController {

    private final OpsMemberLedgerMapper mapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;
    private final ImportTaskService importTaskService;
    private final OrgRoleService orgRoleService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** 安检设备台账创建 — 任务类型标识（与 StoveLedgerCreateHandler 对应）。 */
    private static final String STOVE_LEDGER_TASK_TYPE = "member_ledger_stoves";
    /** PRODUCT_CATEGORY 字典 type_id（品类 key → 中文名） */
    private static final String CATEGORY_DICT_TYPE_ID = "9";

    /** 安检设备品类映射（沿用 StoveLedgerCreateHandler 口径：class 1/2/3 → 产品名与品类 key） */
    private static final Map<String, String> STOVE_CLASS_NAMES =
            Map.of("1", "燃气灶", "2", "热水器", "3", "壁挂炉");
    private static final Map<String, String> STOVE_CLASS_KEYS =
            Map.of("1", "1", "2", "3", "3", "4");
    private static final Map<String, String> INSTALL_POSITION_NAMES =
            Map.of("1", "厨房", "2", "过道", "3", "阳台", "4", "其他");

    /** 会员聚合 SQL：安检/销售两类设备 UNION 后按平台会员分组计数（{...} 占位符由 Java 拼装）。
     *  所属公司=各腿 corp_id → sys_corp.name 聚合；网格员=安检侧 小区→网格员 映射（业务侧无）。 */
    private static final String AGG_SQL = """
            SELECT t.pid AS platform_member_id,
                   COALESCE(NULLIF(m.nickname,''), NULLIF(m.real_name,''), m.phone) AS member_name,
                   m.phone AS member_phone,
                   GROUP_CONCAT(DISTINCT t.community_name ORDER BY t.community_name SEPARATOR '、') AS community_names,
                   GROUP_CONCAT(DISTINCT t.region_name ORDER BY t.region_name SEPARATOR '、') AS region_names,
                   GROUP_CONCAT(DISTINCT c.name ORDER BY c.name SEPARATOR '、') AS corp_names,
                   GROUP_CONCAT(DISTINCT t.gm_name ORDER BY t.gm_name SEPARATOR '、') AS gm_names,
                   SUM(t.is_stove) AS stove_count, SUM(t.is_business) AS business_count
            FROM (
                SELECT gu.platform_member_id AS pid, 1 AS is_stove, 0 AS is_business,
                       sc.community_name, r.region_name, s.create_time AS active_ts,
                       COALESCE(gu.corp_id, s.corp_id) AS corp_id,
                       gm.real_name AS gm_name, gm.id AS gm_id
                FROM ops_gas_stoves_info s
                JOIN ops_gas_user gu ON gu.id = s.gas_user_id AND gu.delete_flag = 0
                LEFT JOIN ai_platform.sys_community sc ON sc.id = gu.community_id AND sc.delete_flag = 0
                LEFT JOIN ai_platform.sys_region r ON r.id = sc.region_id
                -- 小区→网格员：子查询取每小区首个网格员，防 gmc 一小区多行导致设备行翻倍
                LEFT JOIN (SELECT community_id, MIN(grid_manager_id) AS gm_id
                           FROM ops_grid_manager_community GROUP BY community_id) gmc
                       ON gmc.community_id = gu.community_id
                LEFT JOIN ops_grid_manager gm ON gm.id = gmc.gm_id AND gm.delete_flag = 0
                WHERE s.delete_flag = 0 AND gu.platform_member_id IS NOT NULL{scopeStove}
                UNION ALL
                -- 业务腿按「业务组号/收据号 + 产品」分组计数：同组同产品的多经办人行计 1（多经办人同订单），
                -- 不同产品分别计；无组号/收据号时逐行计。口径与 mergeBusinessDevices 一致（__row_ 前缀 key 同构），改动须两侧同步
                SELECT g.pid, 0, g.cnt, NULL, NULL, g.active_ts, g.corp_id, NULL, NULL
                FROM (
                    SELECT bd.platform_member_id AS pid,
                           MAX(bd.corp_id) AS corp_id,
                           MAX(bd.create_time) AS active_ts,
                           CASE WHEN COUNT(DISTINCT NULLIF(bd.agent,'')) > 1 THEN 1 ELSE COUNT(*) END AS cnt
                    FROM ops_business_order_item bd
                    WHERE bd.delete_flag = 0 AND bd.platform_member_id IS NOT NULL
                      AND (bd.sale_quantity IS NULL OR bd.sale_quantity >= 0){scopeBiz}
                    GROUP BY bd.platform_member_id,
                             COALESCE(NULLIF(bd.order_no,''), NULLIF(bd.business_group_no,''), NULLIF(bd.receipt_no,''), CONCAT('__row_', bd.id)),
                             bd.category, bd.brand, bd.model
                ) g
            ) t
            JOIN ai_platform.sys_member m ON m.id = t.pid AND m.delete_flag = 0
            LEFT JOIN ai_platform.sys_corp c ON c.id = t.corp_id AND c.delete_flag = 0
            WHERE 1=1{kwClause}{commClause}{corpClause}{gmClause}
            GROUP BY t.pid, m.nickname, m.real_name, m.phone
            HAVING 1=1{sourceClause}
            """;

    /**
     * 会员聚合列表：仅展示至少关联一台设备（安检采集或销售业务）的会员。
     * 筛选：memberKeyword（姓名/手机号）、communityName（小区，仅安检侧有小区概念）、
     * sources（stove,business 逗号分隔，空=全部）、corpId（所属公司）、gridManagerId（网格员）。
     */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String memberKeyword,
                     @RequestParam(required = false) String communityName,
                     @RequestParam(required = false) String sources,
                     @RequestParam(required = false) String corpId,
                     @RequestParam(required = false) String gridManagerId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String aggSql = AGG_SQL
                .replace("{scopeStove}", buildScopeStoveClause(visible))
                .replace("{scopeBiz}", buildScopeBizClause(visible))
                .replace("{kwClause}", buildKeywordClause(memberKeyword))
                .replace("{commClause}", buildCommunityClause(communityName))
                .replace("{corpClause}", buildCorpClause(corpId))
                .replace("{gmClause}", buildGmClause(gridManagerId))
                .replace("{sourceClause}", buildSourceClause(sources));
        try {
            int page = Math.max(pageNum, 1);
            int size = Math.min(Math.max(pageSize, 1), 200);
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" + aggSql + ") x", Integer.class);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    aggSql + " ORDER BY MAX(t.active_ts) DESC, t.pid DESC LIMIT ?, ?",
                    (page - 1) * size, size);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("records", rows.stream().map(this::toRecord).collect(Collectors.toList()));
            data.put("total", total == null ? 0 : total);
            return R.ok(data);
        } catch (Exception e) {
            // 跨库 sys_member/sys_community 不可达时降级空列表，不阻断页面
            log.warn("member aggregation failed: {}", e.getMessage());
            return R.ok(Map.of("records", List.of(), "total", 0));
        }
    }

    /** 筛选下拉选项：可见组织（sys_corp）与可见网格员（ops_grid_manager），超管全量；跨库不可达降级空列表。 */
    @GetMapping("/filter-options")
    public R<?> filterOptions() {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String scope = visible == null || visible.isEmpty() ? ""
                : " AND id IN (" + visible.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + ")";
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put("corps", jdbcTemplate.queryForList(
                            "SELECT id, name FROM ai_platform.sys_corp WHERE delete_flag = 0" + scope + " ORDER BY id")
                    .stream()
                    .map(r -> Map.of("corpId", ((Number) r.get("id")).longValue(), "corpName", r.get("name")))
                    .collect(Collectors.toList()));
            String gmScope = visible == null || visible.isEmpty() ? ""
                    : " AND corp_id IN (" + visible.stream().map(String::valueOf)
                            .collect(Collectors.joining(",")) + ")";
            data.put("gridManagers", jdbcTemplate.queryForList(
                            "SELECT id, real_name FROM ops_grid_manager WHERE delete_flag = 0" + gmScope
                                    + " ORDER BY corp_id, id")
                    .stream()
                    .map(r -> Map.of("gridManagerId", ((Number) r.get("id")).longValue(), "name", r.get("real_name")))
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("filter options failed: {}", e.getMessage());
            data.put("corps", List.of());
            data.put("gridManagers", List.of());
        }
        return R.ok(data);
    }

    /** 聚合行 → 前端 camelCase 记录（SUM 结果转 long，避免 BigDecimal 序列化差异）。 */
    private Map<String, Object> toRecord(Map<String, Object> row) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("platformMemberId", ((Number) row.get("platform_member_id")).longValue());
        rec.put("memberName", row.get("member_name"));
        rec.put("memberPhone", row.get("member_phone"));
        rec.put("communityNames", row.get("community_names"));
        rec.put("regionNames", row.get("region_names"));
        rec.put("corpNames", row.get("corp_names"));
        rec.put("gridManagerNames", row.get("gm_names"));
        rec.put("stoveCount", ((Number) row.get("stove_count")).longValue());
        rec.put("businessCount", ((Number) row.get("business_count")).longValue());
        return rec;
    }

    /** 会员关联设备明细：安检采集 + 销售业务两组，行内带 source；空组为空列表（前端隐藏）。 */
    @GetMapping("/member/{platformMemberId}/devices")
    public R<?> memberDevices(@PathVariable Long platformMemberId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        try {
            List<Map<String, Object>> stoveRows = queryStoveDevices(
                    platformMemberId, buildScopeStoveClause(visible));
            List<Map<String, Object>> bizRows = mergeBusinessDevices(queryBusinessDevices(
                    platformMemberId, buildScopeBizClause(visible)));
            Map<String, String> catLabels =
                    loadCategoryLabels(collectCategoryKeys(stoveRows, bizRows));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("platformMemberId", platformMemberId);
            data.put("memberName", resolveMemberName(platformMemberId));
            data.put("stoveDevices", sliceDeviceRows(stoveRows).stream()
                    .map(r -> toStoveDevice(r, catLabels)).collect(Collectors.toList()));
            data.put("businessDevices", sliceDeviceRows(bizRows).stream()
                    .map(r -> toBusinessDevice(r, catLabels)).collect(Collectors.toList()));
            data.put("stoveTotal", Math.min(stoveRows.size(), 500));
            data.put("businessTotal", Math.min(bizRows.size(), 500));
            data.put("stoveTruncated", stoveRows.size() > 500);
            data.put("businessTruncated", bizRows.size() > 500);
            return R.ok(data);
        } catch (Exception e) {
            log.warn("member devices failed: {}", e.getMessage());
            return R.ok(Map.of("platformMemberId", platformMemberId,
                    "memberName", "#" + platformMemberId,
                    "stoveDevices", List.of(), "businessDevices", List.of(),
                    "stoveTotal", 0, "businessTotal", 0,
                    "stoveTruncated", false, "businessTruncated", false));
        }
    }

    @OperationLog(module = "增值业务", action = "新增", targetTable = "ops_member_ledger")
    @PostMapping
    @Transactional
    public R<?> create(@RequestBody OpsMemberLedger rec) {
        rec.setId(null);
        rec.setCorpId(resolveCorpId());
        mapper.insert(rec);
        return R.ok(Map.of("id", rec.getId()));
    }

    /**
     * 安检设备创建台账 — 提交异步任务。
     * 权限：仅超管/运营商；城市合伙人不可见、不可调（403）。
     */
    @OperationLog(module = "增值业务", action = "安检设备创建台账", targetTable = "ops_member_ledger")
    @PostMapping("/from-stoves/submit")
    public R<?> submitFromStoves() {
        if (!orgRoleService.isOperatorOrSuperAdmin()) return R.forbidden();
        if (importTaskService.hasPendingTask(STOVE_LEDGER_TASK_TYPE)) {
            return R.fail("已有安检设备创建任务正在执行中，请等待完成后再试");
        }
        // 可见组织集合写入 remark，供 Handler 做数据范围过滤（超管=全量）
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String scopeCsv = (visible == null || visible.isEmpty()) ? ""
                : visible.stream().map(String::valueOf).collect(Collectors.joining(","));
        SysImportTask task = importTaskService.createTaskWithoutFile(
                STOVE_LEDGER_TASK_TYPE, "安检设备创建会员台账", scopeCsv);
        try {
            kafkaTemplate.send("async-import-task",
                    objectMapper.writeValueAsString(Map.of("taskId", task.getId())));
        } catch (Exception e) {
            log.error("Failed to send Kafka message for stove-ledger task {}", task.getId(), e);
            importTaskService.markFailed(task.getId(), "KAFKA",
                    "消息队列不可用，请稍后重试", 0, 0);
            return R.fail("创建任务提交失败，请稍后重试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("async", true);
        data.put("message", "安检设备创建任务已提交，正在后台处理中...");
        return R.ok(data);
    }

    /** 安检设备创建台账 — 任务状态轮询。 */
    @GetMapping("/from-stoves/{taskId}/status")
    public R<?> stovesTaskStatus(@PathVariable Long taskId) {
        SysImportTask task = importTaskService.getById(taskId);
        if (task == null) return R.fail("任务不存在");
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (task.getCorpId() != null && visibleCorpIds != null
                && !visibleCorpIds.contains(task.getCorpId())
                && !visibleCorpIds.contains(0L)) {
            return R.fail("无权限查看该任务");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("phase", task.getPhase());
        result.put("totalRows", task.getTotalRows());
        result.put("successRows", task.getSuccessRows());
        result.put("failRows", task.getFailRows());
        result.put("progressMsg", task.getProgressMsg());
        result.put("errorMsg", task.getErrorMsg());
        result.put("message", task.getProgressMsg());
        return R.ok(result);
    }

    @OperationLog(module = "增值业务", action = "修改", targetTable = "ops_member_ledger")
    @PutMapping("/{id}")
    @Transactional
    public R<?> update(@PathVariable Long id, @RequestBody OpsMemberLedger rec) {
        OpsMemberLedger existing = mapper.selectById(id);
        if (existing == null) return R.fail("记录不存在");
        rec.setId(id);
        rec.setCorpId(existing.getCorpId());
        mapper.updateById(rec);
        return R.ok();
    }

    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_member_ledger")
    @DeleteMapping("/{id}")
    @Transactional
    public R<?> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    // ── 私有 helper ──

    private Long resolveCorpId() {
        Set<Long> ids = dataScopeService.getVisibleCorpIds();
        if (ids == null || ids.isEmpty()) return 4L;
        return ids.iterator().next();
    }

    /** 安检侧范围：设备自身或关联燃气用户任一可见即算（沿用 StoveLedgerCreateHandler 口径）。 */
    private String buildScopeStoveClause(Set<Long> visible) {
        if (visible == null || visible.isEmpty()) return "";
        String csv = visible.stream().map(String::valueOf).collect(Collectors.joining(","));
        return " AND (s.corp_id IN (" + csv + ") OR gu.corp_id IN (" + csv + "))";
    }

    /** 业务侧范围：ops_business_order_item 自身 corp 过滤。 */
    private String buildScopeBizClause(Set<Long> visible) {
        if (visible == null || visible.isEmpty()) return "";
        String csv = visible.stream().map(String::valueOf).collect(Collectors.joining(","));
        return " AND bd.corp_id IN (" + csv + ")";
    }

    /** 姓名/手机号筛选：纯数字=手机号精确匹配；否则昵称/姓名/手机号模糊。 */
    private String buildKeywordClause(String memberKeyword) {
        if (memberKeyword == null || memberKeyword.isBlank()) return "";
        String k = memberKeyword.trim().replace("'", "''");
        if (k.matches("\\d+")) return " AND m.phone = '" + k + "'";
        return " AND (m.nickname LIKE '%" + k + "%' OR m.real_name LIKE '%" + k
                + "%' OR m.phone LIKE '%" + k + "%')";
    }

    /** 小区筛选（业务侧无小区概念，community_name 为 NULL 自然被排除）。 */
    private String buildCommunityClause(String communityName) {
        if (communityName == null || communityName.isBlank()) return "";
        return " AND t.community_name LIKE '%" + communityName.trim().replace("'", "''") + "%'";
    }

    /** 所属公司筛选（数字 corpId；过滤该公司名下设备行，计数随之收缩——与小区筛选同语义）。 */
    private String buildCorpClause(String corpId) {
        if (corpId == null || !corpId.matches("\\d+")) return "";
        return " AND t.corp_id = " + corpId;
    }

    /** 网格员筛选（数字 gridManagerId；仅安检侧有 小区→网格员 映射，业务腿 NULL 自然排除）。 */
    private String buildGmClause(String gridManagerId) {
        if (gridManagerId == null || !gridManagerId.matches("\\d+")) return "";
        return " AND t.gm_id = " + gridManagerId;
    }

    /** 设备来源筛选：stove/business 逗号分隔；空或双选=不过滤。 */
    private String buildSourceClause(String sources) {
        if (sources == null || sources.isBlank()) return "";
        Set<String> set = Arrays.stream(sources.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        boolean stove = set.contains("stove"), biz = set.contains("business");
        if (stove && !biz) return " AND SUM(t.is_stove) > 0";
        if (biz && !stove) return " AND SUM(t.is_business) > 0";
        return "";
    }

    /** 安检采集设备：stoves → gas_user(platform_member_id)；usage_date=安装时间（取日期部分；
     *  buy_time/startup_time 生产恒 NULL 弃用，原 COALESCE 兜底链已删除）。 */
    private List<Map<String, Object>> queryStoveDevices(Long platformMemberId, String scopeStove) {
        String sql = """
                SELECT s.id AS device_id, s.gas_stoves_class AS cls,
                       s.gas_stoves_brand AS brand, s.gas_stoves_type AS model,
                       DATE_FORMAT(s.installation_time, '%Y-%m-%d') AS usage_date,
                       s.installation_position AS install_position, s.focal_eye AS focal_eye, s.power AS power
                FROM ops_gas_stoves_info s
                JOIN ops_gas_user gu ON gu.id = s.gas_user_id AND gu.delete_flag = 0
                WHERE s.delete_flag = 0 AND gu.platform_member_id = ?""" + scopeStove + """
                 ORDER BY (s.installation_time IS NULL), s.installation_time DESC, s.id DESC
                -- 501=500+1 探测超限截断
                LIMIT 501
                """;
        return jdbcTemplate.queryForList(sql, platformMemberId);
    }

    /** 销售设备：ops_business_order_item 按 platform_member_id 归属（回填规则见迁移脚本）；
     *  全量拉取（单会员行数有限）供 mergeBusinessDevices 按收据号合并，合并后再截断 500。
     *  使用日期=市场收款日期（receipt_date 全量 ISO yyyy-MM-dd，usage_date 列为死数据恒 NULL）。 */
    private List<Map<String, Object>> queryBusinessDevices(Long platformMemberId, String scopeBiz) {
        String sql = """
                SELECT bd.id AS device_id, bd.category AS category_key, bd.brand, bd.model,
                       bd.sale_price, bd.sale_date, bd.receipt_date, bd.receipt_no,
                       bd.order_no, bd.business_group_no, bd.agent, bd.remark, bd.customer,
                       bd.receipt_date AS usage_date
                FROM ops_business_order_item bd
                WHERE bd.delete_flag = 0 AND bd.platform_member_id = ?
                  AND (bd.sale_quantity IS NULL OR bd.sale_quantity >= 0)""" + scopeBiz + """
                 ORDER BY (bd.receipt_date IS NULL), bd.receipt_date DESC, bd.id DESC
                """;
        return jdbcTemplate.queryForList(sql, platformMemberId);
    }

    /**
     * 销售设备按「业务组号/收据号 + 产品」合并：
     * 组号优先（无则收据号，均无则逐行独立组）；组内 distinct 非空经办人 >1（多经办人同订单）→
     * 仅保留 id 最小行（"第一条"，与导出 sortByReceiptNo 首行口径一致），
     * 经办人替换为全员名单并打多人参与标记；经办人唯一 → 全部保留（同经办人多商品逐条展示）。
     * 产品维度（品类/品牌/型号）入键：同一笔业务多产品分别展示多条。
     * 口径与 AGG_SQL 业务腿一致（__row_ 前缀分组 key 同构），改动须两侧同步。
     */
    private List<Map<String, Object>> mergeBusinessDevices(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String orderNo = (String) row.get("order_no");
            String groupNo = (String) row.get("business_group_no");
            String receiptNo = (String) row.get("receipt_no");
            String base;
            if (orderNo != null && !orderNo.isEmpty()) base = orderNo;
            else if (groupNo != null && !groupNo.isEmpty()) base = groupNo;
            else if (receiptNo != null && !receiptNo.isEmpty()) base = receiptNo;
            else base = "__row_" + row.get("device_id");
            String key = base + "|" + row.get("category_key") + "|"
                    + row.get("brand") + "|" + row.get("model");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            group.sort(Comparator.comparing(r -> ((Number) r.get("device_id")).longValue()));
            Map<String, Object> rep = group.get(0);
            LinkedHashSet<String> agents = new LinkedHashSet<>();
            for (Map<String, Object> row : group) {
                Object agent = row.get("agent");
                if (agent != null && !agent.toString().trim().isEmpty()) {
                    agents.add(agent.toString().trim());
                }
            }
            if (agents.size() > 1) {
                String joined = String.join("、", agents);
                rep.put("agent", joined);
                rep.put("__multi_agent", Boolean.TRUE);
                rep.put("__agent_names", joined);
                merged.add(rep);
            } else {
                merged.addAll(group);
            }
        }
        // 显示序与 SQL 一致：usage_date NULL 沉底 → 降序（DATE_FORMAT 字典序=时间序）→ id 降序
        merged.sort(Comparator
                .comparing((Map<String, Object> r) -> (String) r.get("usage_date"),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> ((Number) r.get("device_id")).longValue(),
                        Comparator.reverseOrder()));
        return merged;
    }

    /** 品类 key 集合：安检侧来自 class 映射常量，业务侧来自 category 列（均为字典 item_key）。 */
    private Set<String> collectCategoryKeys(List<Map<String, Object>> stoveRows,
                                            List<Map<String, Object>> bizRows) {
        Set<String> keys = new HashSet<>();
        stoveRows.forEach(r -> {
            String key = r.get("cls") == null ? null : STOVE_CLASS_KEYS.get(r.get("cls"));
            if (key != null) keys.add(key);
        });
        bizRows.forEach(r -> {
            if (r.get("category_key") != null) keys.add((String) r.get("category_key"));
        });
        return keys;
    }

    /** 品类 key → 字典中文（PRODUCT_CATEGORY，type_id=9）；不可达时回退空 map（调用方用 key 兜底）。 */
    private Map<String, String> loadCategoryLabels(Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String in = keys.stream().map(k -> "'" + k.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.queryForList(
                    "SELECT item_key, item_value FROM ai_platform.sys_dict_item "
                    + "WHERE type_id = " + CATEGORY_DICT_TYPE_ID + " AND delete_flag = 0 "
                    + "AND item_key IN (" + in + ")")
                    .forEach(row -> map.put((String) row.get("item_key"),
                            (String) row.get("item_value")));
        } catch (Exception e) { log.warn("categoryLabels failed: {}", e.getMessage()); }
        return map;
    }

    /** 超 500 行截断，前端展示「仅展示前 500 台」提示（业务侧在合并后截断；安检侧 SQL 取 501 探测）。 */
    private List<Map<String, Object>> sliceDeviceRows(List<Map<String, Object>> rows) {
        return rows.size() > 500 ? new ArrayList<>(rows.subList(0, 500)) : rows;
    }

    /** 安检设备行 → 前端展示结构（其他属性=安装位置/灶眼/功率，与台账 remark 同口径）。 */
    private Map<String, Object> toStoveDevice(Map<String, Object> row, Map<String, String> catLabels) {
        // gas_stoves_class 可能为 NULL/未知值（生产存在），Map.of 的 get(null) 会抛 NPE，须守卫
        String cls = (String) row.get("cls");
        String key = cls == null ? null : STOVE_CLASS_KEYS.get(cls);
        Map<String, String> attrs = new LinkedHashMap<>();
        Object posVal = row.get("install_position");
        String pos = posVal == null ? null : INSTALL_POSITION_NAMES.get(posVal);
        if (pos != null) attrs.put("安装位置", pos);
        putAttr(attrs, "灶眼", row.get("focal_eye"));
        putAttr(attrs, "功率", row.get("power"));
        String name = cls == null ? "其他" : STOVE_CLASS_NAMES.getOrDefault(cls, "其他");
        return device(row, "stove", name, labelOf(catLabels, key), attrs);
    }

    /** 销售设备行 → 前端展示结构（业务表无产品名/档次，产品名用品类中文；其他属性=销售信息）。 */
    private Map<String, Object> toBusinessDevice(Map<String, Object> row, Map<String, String> catLabels) {
        String key = (String) row.get("category_key");
        String label = labelOf(catLabels, key);
        Map<String, String> attrs = new LinkedHashMap<>();
        putAttr(attrs, "销售价", row.get("sale_price"));
        putAttr(attrs, "销售日期", row.get("sale_date"));
        putAttr(attrs, "市场收款日期", row.get("receipt_date"));
        putAttr(attrs, "收据号", row.get("receipt_no"));
        putAttr(attrs, "客户", row.get("customer"));
        // 合并代表行的 agent 已被 mergeBusinessDevices 替换为全员名单，此处直接展示
        putAttr(attrs, "经办人", row.get("agent"));
        putAttr(attrs, "备注", row.get("remark"));
        Map<String, Object> d = device(row, "business", label, label, attrs);
        if (Boolean.TRUE.equals(row.get("__multi_agent"))) {
            d.put("multiAgent", Boolean.TRUE);
            d.put("agentNames", row.get("__agent_names"));
        }
        return d;
    }

    /** 字典 label 兜底：key 为空 → "-"；未命中 → key 本身（Map.of 空 map + null key 会 NPE，须守卫）。 */
    private String labelOf(Map<String, String> catLabels, String key) {
        if (key == null) return "-";
        String v = catLabels.get(key);
        return v != null ? v : key;
    }

    /** 设备行公共结构；来源标签由前端按 source 渲染。 */
    private Map<String, Object> device(Map<String, Object> row, String source,
                                       String productName, String categoryLabel,
                                       Map<String, String> attrs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("productName", productName);
        d.put("category", categoryLabel);
        d.put("brand", row.get("brand"));
        d.put("model", row.get("model"));
        d.put("grade", null);   // 两来源均无档次数据，前端显示 "-"
        d.put("usageDate", row.get("usage_date"));
        d.put("attrs", attrs);
        return d;
    }

    private void putAttr(Map<String, String> attrs, String label, Object value) {
        if (value == null) return;
        String s = String.valueOf(value).trim();
        if (!s.isEmpty()) attrs.put(label, s);
    }

    /** 会员显示名（昵称 → 姓名 → 手机号），缺失回退 #id。 */
    private String resolveMemberName(Long platformMemberId) {
        try {
            String name = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(NULLIF(nickname,''), NULLIF(real_name,''), phone) "
                    + "FROM ai_platform.sys_member WHERE id = ? AND delete_flag = 0",
                    String.class, platformMemberId);
            return name != null ? name : "#" + platformMemberId;
        } catch (Exception e) {
            log.warn("member name failed: {}", e.getMessage());
            return "#" + platformMemberId;
        }
    }
}
