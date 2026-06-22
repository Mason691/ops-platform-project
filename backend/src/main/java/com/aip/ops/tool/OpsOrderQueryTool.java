package com.aip.ops.tool;

import com.aip.ops.entity.GsOrder;
import com.aip.ops.mapper.GsOrderMapper;
import com.aip.sdk.annotation.McpTool;
import com.aip.sdk.mcp.McpToolDefinition;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 金地燃气订单查询 MCP 工具。
 * 注册到底座平台后，AI 助手可通过 MCP 协议查询用户订单。
 */
@McpTool
@Component
@RequiredArgsConstructor
public class OpsOrderQueryTool implements McpToolDefinition {

    private final GsOrderMapper orderMapper;

    @Override
    public String getName() {
        return "ops_query_order";
    }

    @Override
    public String getDescription() {
        return "查询当前用户在金地燃气运营平台的订单。支持按订单号和状态过滤，返回最近订单列表。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("orderNo", Map.of("type", "string", "description", "订单号（可选，精确匹配）"));
        props.put("status", Map.of("type", "string", "description", "订单状态（可选）：pending/paid/expired/cancelled"));
        props.put("limit", Map.of("type", "integer", "description", "返回条数，默认10，最多20", "default", 10));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, Long corpId, Long userId) {
        String orderNo = args.get("orderNo") instanceof String ? (String) args.get("orderNo") : null;
        String status = args.get("status") instanceof String ? (String) args.get("status") : null;
        int limit = args.get("limit") instanceof Number
                ? Math.min(((Number) args.get("limit")).intValue(), 20) : 10;

        List<GsOrder> orders = orderMapper.selectList(
                new LambdaQueryWrapper<GsOrder>()
                        .eq(GsOrder::getUserId, userId)
                        .eq(GsOrder::getCorpId, corpId)
                        .eq(orderNo != null && !orderNo.isEmpty(), GsOrder::getOrderNo, orderNo)
                        .eq(status != null && !status.isEmpty(), GsOrder::getStatus, status)
                        .orderByDesc(GsOrder::getId)
                        .last("LIMIT " + limit));

        List<Map<String, Object>> list = orders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("orderNo", o.getOrderNo());
            m.put("productName", o.getProductName());
            m.put("pointsSpent", o.getPointsSpent());
            m.put("cashAmount", o.getCashAmount());
            m.put("status", o.getStatus());
            m.put("createTime", o.getCreateTime());
            return m;
        }).toList();

        return Map.of("total", list.size(), "data", list);
    }
}
