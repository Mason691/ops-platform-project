package com.aip.ops.agent;

import com.aip.ops.mapper.GsOrderMapper;
import com.aip.ops.mapper.GsProductMapper;
import com.aip.sdk.agent.SdkDomainAgent;
import com.aip.sdk.annotation.AgentComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营平台 DomainAgent。
 * 向底座注册后，AI 助手可将运营相关综合问题路由到此 Agent 处理。
 *
 * 设计原则：简单数据查询优先使用 MCP 工具（如 OpsOrderQueryTool），
 * 复杂多步骤推理使用 Agent。
 */
@Slf4j
@AgentComponent
@RequiredArgsConstructor
public class OpsAgent implements SdkDomainAgent {

    private final GsOrderMapper orderMapper;
    private final GsProductMapper productMapper;

    @Override
    public String name() {
        return "OPS";
    }

    @Override
    public String description() {
        return "运营平台 Agent，负责运营分析、积分兑换咨询、活动查询推荐等综合业务。";
    }

    @Override
    public Map<String, Object> handle(String userMessage, Map<String, Object> context) {
        Long corpId = context.get("corpId") instanceof Number
                ? ((Number) context.get("corpId")).longValue() : null;

        // 统计当前运营数据
        long orderCount = orderMapper.selectCount(null);
        long productCount = productMapper.selectCount(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", String.format(
                "运营平台当前共有 %d 件积分商品、累计 %d 笔兑换订单。" +
                "如需查询具体订单、推荐商品或了解活动详情，请告诉我您的需求。",
                productCount, orderCount));
        result.put("orderCount", orderCount);
        result.put("productCount", productCount);

        log.info("OpsAgent 处理请求: corpId={}, orderCount={}, productCount={}",
                corpId, orderCount, productCount);
        return result;
    }
}
