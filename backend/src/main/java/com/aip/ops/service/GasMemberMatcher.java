package com.aip.ops.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 业务数据导入的会员归属匹配（2026-10-19 需求：匹配不到会员时留空，不阻断导入）。
 * <p>
 * 匹配范围：导入者组织 → {@code ai_platform.sys_corp.gas_company_id}（跨库直读），
 * 仅在该燃气公司范围内的燃气用户（{@code ops_gas_user}，外部 sb_gas_user 的本地镜像）中
 * 按 客户手机 → 客户名称 顺序匹配，取燃气用户的 platform_member_id。
 * 组织无 gas_company_id（超管/运营商）时回退全域匹配。
 * </p>
 * <p>同手机/同名多行确定性取行：优先 platform_member_id 非空者，再取最小 id。
 * 返回 map 的 key 存在即「命中燃气用户」，value 可能为 null（燃气用户尚未转化会员）。</p>
 */
@Component
public class GasMemberMatcher {

    private final JdbcTemplate jdbcTemplate;

    private static final int CHUNK = 500;

    /** 大陆手机号：1 开头、第二位 3-9、共 11 位（导入校验与重匹配共用口径） */
    public static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    public GasMemberMatcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 组织 → 燃气公司 ID（跨库读 ai_platform.sys_corp）；无配置返回 null（回退全域匹配）。 */
    public String resolveGasCompanyId(Long corpId) {
        if (corpId == null || corpId == 0L) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT gas_company_id FROM ai_platform.sys_corp "
                            + "WHERE id = ? AND delete_flag = 0", corpId);
            if (rows.isEmpty()) return null;
            Object v = rows.get(0).get("gas_company_id");
            if (v == null) return null;
            String s = v.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            // 底座库不可达时回退全域匹配，不阻断导入
            return null;
        }
    }

    /** 按手机号批量匹配（限定燃气公司范围；gasCompanyId 为 null 时全域）。 */
    public Map<String, Long> memberIdByPhone(List<String> phones, String gasCompanyId) {
        return lookup("phone", phones, gasCompanyId);
    }

    /** 按姓名批量匹配（限定燃气公司范围；gasCompanyId 为 null 时全域）。 */
    public Map<String, Long> memberIdByName(List<String> names, String gasCompanyId) {
        return lookup("full_name", names, gasCompanyId);
    }

    /** 全局按手机号匹配会员（uk_phone 唯一，跨组织直接查 ai_platform.sys_member）。 */
    public Map<String, Long> memberIdByPhoneGlobal(List<String> phones) {
        if (phones == null || phones.isEmpty()) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < phones.size(); i += CHUNK) {
            List<String> chunk = phones.subList(i, Math.min(i + CHUNK, phones.size()));
            String sql = "SELECT phone, id FROM ai_platform.sys_member "
                    + "WHERE delete_flag = 0 AND phone IN ("
                    + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, chunk.toArray())) {
                result.putIfAbsent((String) row.get("phone"),
                        ((Number) row.get("id")).longValue());
            }
        }
        return result;
    }

    /** 按公司（corp）+ 姓名匹配会员：real_name/nickname 任一命中，同名取最小 id。 */
    public Map<String, Long> memberIdByNameInCorp(List<String> names, Long corpId) {
        if (names == null || names.isEmpty() || corpId == null) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < names.size(); i += CHUNK) {
            List<String> chunk = names.subList(i, Math.min(i + CHUNK, names.size()));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT nm, MIN(id) AS id FROM ("
                    + " SELECT real_name AS nm, id FROM ai_platform.sys_member"
                    + " WHERE delete_flag = 0 AND corp_id = ? AND real_name IN (" + in + ")"
                    + " UNION ALL"
                    + " SELECT nickname AS nm, id FROM ai_platform.sys_member"
                    + " WHERE delete_flag = 0 AND corp_id = ? AND nickname IN (" + in + ")"
                    + ") x GROUP BY nm";
            Object[] args = new Object[chunk.size() * 2 + 2];
            args[0] = corpId;
            for (int j = 0; j < chunk.size(); j++) args[1 + j] = chunk.get(j);
            args[1 + chunk.size()] = corpId;
            for (int j = 0; j < chunk.size(); j++) args[2 + chunk.size() + j] = chunk.get(j);
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
                result.put((String) row.get("nm"), ((Number) row.get("id")).longValue());
            }
        }
        return result;
    }

    /** 通用批量查询：key 命中即有燃气用户；value=platform_member_id（可能为 null=未转化会员）。 */
    private Map<String, Long> lookup(String column, List<String> keys, String gasCompanyId) {
        if (keys == null || keys.isEmpty()) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < keys.size(); i += CHUNK) {
            List<String> chunk = keys.subList(i, Math.min(i + CHUNK, keys.size()));
            StringBuilder sql = new StringBuilder("SELECT ").append(column)
                    .append(", platform_member_id FROM ops_gas_user WHERE delete_flag = 0");
            if (gasCompanyId != null && !gasCompanyId.isBlank()) {
                sql.append(" AND gas_company_id = ?");
            }
            sql.append(" AND ").append(column).append(" IN (")
                    .append(String.join(",", Collections.nCopies(chunk.size(), "?")))
                    .append(") ORDER BY (platform_member_id IS NULL), id");
            Object[] args;
            if (gasCompanyId != null && !gasCompanyId.isBlank()) {
                args = new Object[chunk.size() + 1];
                args[0] = gasCompanyId;
                for (int j = 0; j < chunk.size(); j++) args[j + 1] = chunk.get(j);
            } else {
                args = chunk.toArray();
            }
            // ORDER BY 已保证首行最优，putIfAbsent 取首行
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args)) {
                String key = row.get(column) != null ? row.get(column).toString() : null;
                if (key == null) continue;
                Object memberId = row.get("platform_member_id");
                result.putIfAbsent(key, memberId != null
                        ? ((Number) memberId).longValue() : null);
            }
        }
        return result;
    }

    /**
     * 单行归属决策（导入与手工重匹配共用，口径一致）：
     * 手机号命中燃气用户即定（member 可能为 null）→ 姓名兜底 → null（未匹配，不阻断）。
     * 手机与名称同时命中不同用户时以手机号结果为准。
     */
    public static Long pickMember(String phone, String name,
                                  Map<String, Long> byPhone, Map<String, Long> byName) {
        if (phone != null && byPhone.containsKey(phone)) return byPhone.get(phone);
        if (name != null && !name.isBlank() && byName.containsKey(name.trim())) {
            return byName.get(name.trim());
        }
        return null;
    }

    /**
     * 全局查已有会员（创建前兜底复用；uk_phone 全局唯一）。
     * 返回 null = 不存在；存在但已逻辑删除 → 抛异常（该手机号被占用，需人工处理）。
     */
    public Long findMemberIdByPhone(String phone) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, delete_flag FROM ai_platform.sys_member WHERE phone = ? LIMIT 1", phone);
        if (rows.isEmpty()) return null;
        Object flag = rows.get(0).get("delete_flag");
        if (flag != null && ((Number) flag).intValue() != 0) {
            throw new IllegalStateException("手机号 " + phone
                    + " 已存在已删除的会员记录，请人工处理后重试");
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }

    /**
     * 在底座平台创建新会员（燃气公司范围内无用户、全局无同手机号会员时的兜底）。
     * 字段：phone/nickname/real_name 取导入行，status=1，remark 标注来源，
     * 画像字段（gender/birthday/address/community_id）Excel 无对应列，留空待后续补充。
     * corp_id 取导入组织。INSERT 模式镜像 MemberConvertHandler（GeneratedKeyHolder 取自增 id）。
     */
    public Long createMember(String phone, String name, Long corpId) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO ai_platform.sys_member " +
                        "(phone, nickname, real_name, status, remark, corp_id) " +
                        "VALUES (?,?,?,1,'业务数据导入自动创建',?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, phone);
                ps.setString(2, name);
                ps.setString(3, name);
                ps.setLong(4, corpId != null ? corpId : 0L);
                return ps;
            }, keyHolder);
            return keyHolder.getKey().longValue();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_phone 冲突多为并发导入同手机号：整体回滚重试即可
            throw new IllegalStateException("手机号 " + phone + " 会员创建冲突（并发导入？），请重试", e);
        }
    }
}
