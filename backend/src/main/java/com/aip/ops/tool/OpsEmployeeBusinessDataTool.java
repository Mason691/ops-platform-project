package com.aip.ops.tool;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsBusinessDataEmployeeMapper;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.impl.BusinessDataServiceImpl;
import com.aip.sdk.annotation.McpTool;
import com.aip.sdk.mcp.McpToolDefinition;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Employee business data query MCP tool.
 * Registered with the base platform via {@code @McpTool} auto-discovery.
 * <p>
 * All queries are scoped by {@code agent_phone} resolved from the current user's
 * member record — employees can only see their own business data.
 */
@Slf4j
@McpTool
@Component
@RequiredArgsConstructor
public class OpsEmployeeBusinessDataTool implements McpToolDefinition {

    private final OpsMemberMapper memberMapper;
    private final OpsBusinessDataEmployeeMapper employeeMapper;

    @Override
    public String getName() {
        return "ops_query_employee_business_data";
    }

    @Override
    public String getDescription() {
        return "查询员工的增值业务数据：按月份查询销售额、提成金额、订单数，支持按品类筛选和查看明细。" +
               "数据范围限定为当前员工手机号关联的业务记录。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("month", Map.of(
                "type", "string",
                "description", "查询月份，格式 YYYY-MM，如 2026-06。不传则返回最近有数据的月份"));
        props.put("category", Map.of(
                "type", "string",
                "description", "品类名称（可选）：燃气灶/抽油烟机/热水器/壁挂炉/壁挂炉以旧换新销售/户内美装开发/定尺波纹管/户内美装工程/户内美装运维"));
        props.put("detail", Map.of(
                "type", "boolean",
                "description", "是否返回明细记录，默认 false 只返回汇总",
                "default", false));
        props.put("limit", Map.of(
                "type", "integer",
                "description", "明细返回条数上限，默认10，最多50",
                "default", 10));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, Long corpId, Long userId) {
        // Resolve employee's phone from member record
        String phone = resolvePhone(userId, corpId);
        if (phone == null) {
            return Map.of("error", "未找到员工信息，请确认已注册为员工");
        }

        String month = args.get("month") instanceof String ? (String) args.get("month") : null;
        String category = args.get("category") instanceof String ? (String) args.get("category") : null;
        boolean detail = args.get("detail") instanceof Boolean && (Boolean) args.get("detail");
        int limit = args.get("limit") instanceof Number
                ? Math.min(((Number) args.get("limit")).intValue(), 50) : 10;

        // Resolve month if not specified
        if (month == null || month.isBlank()) {
            List<String> months = employeeMapper.selectAvailableMonths(phone);
            if (months.isEmpty()) {
                return Map.of("message", "暂无业务数据", "data", List.of());
            }
            month = months.get(0); // most recent
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);

        if (detail) {
            // Detail records
            List<Map<String, Object>> rows = employeeMapper.selectDetailRecords(
                    phone, month, category);
            List<Map<String, Object>> list = new ArrayList<>();
            int count = 0;
            for (Map<String, Object> row : rows) {
                if (count >= limit) break;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("brand", row.get("brand"));
                item.put("model", row.get("model"));
                item.put("salePrice", row.get("salePrice"));
                item.put("commissionAmount", row.get("commissionAmount"));
                item.put("saleDate", row.get("saleDate"));
                item.put("customer", row.get("customer"));
                list.add(item);
                count++;
            }
            result.put("total", rows.size());
            result.put("data", list);
        } else if (category != null && !category.isBlank()) {
            // Single category summary
            List<Map<String, Object>> rows = employeeMapper.selectDetailRecords(
                    phone, month, category);
            BigDecimal totalSale = BigDecimal.ZERO;
            BigDecimal totalCommission = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                totalSale = totalSale.add(toBigDecimal(row.get("salePrice")));
                totalCommission = totalCommission.add(toBigDecimal(row.get("commissionAmount")));
            }
            result.put("category", category);
            result.put("totalSale", totalSale.setScale(2, RoundingMode.HALF_UP));
            result.put("totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
            result.put("orderCount", rows.size());
        } else {
            // Category-level summary
            List<Map<String, Object>> summary = employeeMapper.selectCategorySummary(phone, month);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : summary) {
                Map<String, Object> item = new LinkedHashMap<>();
                String catKey = (String) row.get("category");
                item.put("category", BusinessDataServiceImpl.KEY_TO_CN.getOrDefault(catKey, catKey));
                item.put("totalSale", row.get("totalSale"));
                item.put("totalCommission", row.get("totalCommission"));
                item.put("orderCount", row.get("orderCount"));
                list.add(item);
            }
            result.put("data", list);

            // Monthly totals
            Map<String, Object> stats = employeeMapper.selectMonthlyStats(phone, month);
            result.put("totalSale", stats.get("totalSale"));
            result.put("totalCommission", stats.get("totalCommission"));
            result.put("totalOrders", stats.get("orderCount"));
        }

        return result;
    }

    /**
     * Resolve employee phone from member record.
     * Uses JWT member_id claim (sys_member.id) for correct platformMemberId matching,
     * falling back to JWT sub (sys_user.id) for member-JWT callers.
     */
    private String resolvePhone(Long userId, Long corpId) {
        Long memberId = resolveMemberId(userId);
        OpsMember member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, memberId)
                .eq(OpsMember::getCorpId, corpId));
        if (member == null) {
            member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                    .eq(OpsMember::getId, memberId)
                    .eq(OpsMember::getCorpId, corpId));
        }
        return member != null ? member.getPhone() : null;
    }

    private static Long resolveMemberId(Long fallbackUserId) {
        try {
            var user = com.aip.sdk.security.SecurityUtils.getCurrentUser();
            return user.getMemberId() != null ? user.getMemberId() : fallbackUserId;
        } catch (Exception e) {
            return fallbackUserId;
        }
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
