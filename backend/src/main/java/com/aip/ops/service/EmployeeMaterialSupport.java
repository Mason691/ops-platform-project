package com.aip.ops.service;

import com.aip.ops.client.ScmBaseDataClient;
import com.aip.ops.dto.MaterialOptionsView;
import com.aip.ops.dto.MaterialSearchResult;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 员工物资域公共支撑：员工身份/组织/部门、同组织员工、供应链商品类别与商品。
 *
 * <p>员工校验口径与「业务数据订单」制单保持一致（拒绝 SSO 会员令牌、校验身份、
 * 组织必选）；本类只服务物资领用/核销，避免改动已上线的业务订单制单流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeMaterialSupport {

    private static final int MAX_PARTICIPANTS = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformInternalClient;
    private final JwtTokenInspector jwtTokenInspector;
    private final ScmBaseDataClient scmBaseDataClient;

    /** 当前登录员工上下文（corpId 必填；部门可空）。 */
    public EmployeeContext context() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.of("仅员工账号可使用物资领用");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请选择所属公司后再操作");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, Object> user = loadUser(userId);
        String username = str(user.get("username"));
        String phone = str(user.get("phone"));
        String jwtUsername = SecurityUtils.getCurrentUser() == null
                ? "" : str(SecurityUtils.getCurrentUser().getUsername());
        if (!username.equals(jwtUsername) && !phone.equals(jwtUsername)) {
            throw BusinessException.of("仅员工账号可使用物资领用");
        }
        String name = str(user.get("real_name"));
        if (name.isEmpty()) name = username;
        Long deptId = longVal(user.get("dept_id"));
        String deptName = deptName(deptId);
        CorpInfo corp = corpInfo(corpId);
        return new EmployeeContext(corpId, corp.corpName(), corp.company(), userId, name,
                phone.isEmpty() ? jwtUsername : phone, deptId, deptName);
    }

    public MaterialOptionsView options() {
        EmployeeContext ctx = context();
        List<MaterialOptionsView.EmployeeView> employees = employees(ctx.corpId(), ctx.userId());
        List<MaterialOptionsView.CategoryView> categories = categories(ctx.corpId());
        String productError = categories.isEmpty()
                ? "供应链商品类别为空，请联系管理员在供应链管理模块同步商品资料" : null;
        return new MaterialOptionsView(ctx.corpId(), ctx.corpName(), ctx.company(),
                ctx.userId(), ctx.name(), ctx.phone(), ctx.deptId(), ctx.deptName(),
                employees, categories, productError);
    }

    /** 商品搜索：按类别 + 名称/编码/型号/品牌关键字过滤（内存过滤，数据源为 SCM 缓存）。 */
    public MaterialSearchResult searchMaterials(Long corpId, String category, String keyword,
                                                long pageNum, long pageSize) {
        List<ScmBaseDataClient.MaterialOption> all = scmBaseDataClient.options(corpId).materials();
        String cat = trim(category);
        String word = trim(keyword);
        String lower = word == null ? "" : word.toLowerCase(Locale.ROOT);
        List<ScmBaseDataClient.MaterialOption> filtered = all.stream()
                .filter(m -> cat == null || cat.equals(m.category()))
                .filter(m -> matches(m, lower))
                .sorted(Comparator.comparing(ScmBaseDataClient.MaterialOption::category,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ScmBaseDataClient.MaterialOption::name,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ScmBaseDataClient.MaterialOption::model,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
        long total = filtered.size();
        int size = (int) Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        int from = (int) Math.min(Math.max(0, (pageNum - 1) * size), total);
        int to = Math.min(from + size, filtered.size());
        List<MaterialSearchResult.MaterialOptionView> records = filtered.subList(from, to)
                .stream()
                .map(m -> new MaterialSearchResult.MaterialOptionView(m.id(), m.number(),
                        m.name(), m.category(), m.brand(), m.model(), m.unit()))
                .toList();
        return new MaterialSearchResult(records, total);
    }

    public ScmBaseDataClient.MaterialOption requireMaterial(Long corpId, Long materialId) {
        return scmBaseDataClient.requireMaterial(corpId, materialId);
    }

    public List<MaterialOptionsView.CategoryView> categories(Long corpId) {
        return scmBaseDataClient.options(corpId).categories().stream()
                .map(c -> new MaterialOptionsView.CategoryView(c.key(), c.name()))
                .toList();
    }

    /**
     * 校验领用人/核销人：必须属于当前组织、必须包含本人、最多 20 人。
     * 返回按选择顺序排列的 ID/姓名字符串（用于落库与展示）。
     */
    public ParticipantSelection participants(Long corpId, Long selfUserId, List<Long> requested) {
        Set<Long> ids = new LinkedHashSet<>();
        if (requested != null) {
            requested.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (selfUserId != null) ids.add(selfUserId);
        if (ids.isEmpty()) {
            throw BusinessException.of("请选择人员");
        }
        if (ids.size() > MAX_PARTICIPANTS) {
            throw BusinessException.of("单笔最多选择" + MAX_PARTICIPANTS + "人");
        }
        Map<Long, MaterialOptionsView.EmployeeView> map = new LinkedHashMap<>();
        employees(corpId, selfUserId).forEach(e -> map.put(e.userId(), e));
        List<String> names = new ArrayList<>();
        for (Long id : ids) {
            MaterialOptionsView.EmployeeView employee = map.get(id);
            if (employee == null) {
                throw BusinessException.of("所选人员不属于当前组织");
            }
            names.add(employee.name());
        }
        String csv = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return new ParticipantSelection(new ArrayList<>(ids), csv, String.join("、", names));
    }

    // ── 查询 ──────────────────────────────────────────────────────────

    private List<MaterialOptionsView.EmployeeView> employees(Long corpId, Long selfUserId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT u.id AS userId, COALESCE(NULLIF(u.real_name,''), u.username) AS name, "
                        + "u.phone, u.dept_id AS deptId, d.dept_name AS deptName "
                        + "FROM ai_platform.sys_user u "
                        + "LEFT JOIN ai_platform.sys_dept d ON d.id = u.dept_id AND d.delete_flag = 0 "
                        + "WHERE u.status = 1 AND u.delete_flag = 0 "
                        + "AND (u.corp_id = ? OR EXISTS ("
                        + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                        + "  WHERE uc.user_id = u.id AND uc.corp_id = ? "
                        + "  AND uc.status = 1 AND uc.delete_flag = 0)) "
                        + "ORDER BY name, u.id",
                corpId, corpId);
        List<MaterialOptionsView.EmployeeView> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long userId = longVal(row.get("userId"));
            result.add(new MaterialOptionsView.EmployeeView(userId, str(row.get("name")),
                    str(row.get("phone")), longVal(row.get("deptId")), str(row.get("deptName")),
                    Objects.equals(userId, selfUserId)));
        }
        return result;
    }

    private Map<String, Object> loadUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, real_name, phone, status, delete_flag, dept_id "
                        + "FROM ai_platform.sys_user WHERE id = ? LIMIT 1", userId);
        if (rows.isEmpty()) {
            throw BusinessException.of("仅员工账号可使用物资领用");
        }
        Map<String, Object> row = rows.get(0);
        if (!Objects.equals(intVal(row.get("status")), 1)
                || !Objects.equals(intVal(row.get("delete_flag")), 0)) {
            throw BusinessException.of("员工账号已被禁用，请联系管理员");
        }
        return row;
    }

    private String deptName(Long deptId) {
        if (deptId == null) return null;
        List<String> names = jdbcTemplate.queryForList(
                "SELECT dept_name FROM ai_platform.sys_dept WHERE id=? AND delete_flag=0 LIMIT 1",
                String.class, deptId);
        return names.isEmpty() ? null : names.get(0);
    }

    private CorpInfo corpInfo(Long corpId) {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/" + corpId, null);
            Object dataObj = resp.get("data");
            if (dataObj instanceof Map<?, ?> data) {
                String name = str(data.get("name"));
                String shortName = str(data.get("shortName"));
                String company = shortName.isEmpty() ? name : shortName;
                return new CorpInfo(name.isEmpty() ? company : name, company);
            }
        } catch (Exception e) {
            log.warn("物资领用获取组织信息失败 corpId={}: {}", corpId, e.getMessage());
        }
        return new CorpInfo("当前组织", "当前组织");
    }

    private boolean matches(ScmBaseDataClient.MaterialOption m, String lower) {
        if (lower.isEmpty()) return true;
        return contains(m.name(), lower) || contains(m.number(), lower)
                || contains(m.model(), lower) || contains(m.brand(), lower);
    }

    private static boolean contains(String value, String lower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lower);
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String trim(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer intVal(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Long longVal(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { /* ignore */ }
        }
        return null;
    }

    public record EmployeeContext(Long corpId, String corpName, String company, Long userId,
                                  String name, String phone, Long deptId, String deptName) {}

    public record ParticipantSelection(List<Long> ids, String idsCsv, String namesCsv) {}

    private record CorpInfo(String corpName, String company) {}
}
