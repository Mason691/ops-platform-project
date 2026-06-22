package com.aip.ops.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 批量查询商品基本信息的客户端。
 * 调用 mall-project 的 POST /api/internal/product/batch-query（X-Internal-Token 认证），
 * 替代跨库 JOIN mall_db.mall_product。
 *
 * GsProductMapper 中的 7 个 JOIN 查询逐步迁移为此模式：
 * 1. 先查 ops_product 表
 * 2. 收集 mall_product_id 集合
 * 3. 调用 batchQuery 获取商品详情
 * 4. 在 Service 层组装结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductBatchClient {

    private final RestTemplate restTemplate;

    @Value("${platform.internal-token}")
    private String internalToken;

    /** mall-project 地址（与 platform.base-url 不同，mall 是独立服务） */
    @Value("${mall-service.base-url:http://localhost:8083}")
    private String mallServiceBaseUrl;

    /**
     * 批量获取商品名称、封面、售价等基本信息。
     * @param productIds 商品 ID 列表
     * @return productId → {name, coverImage, salePrice, status, corpId}
     */
    @SuppressWarnings("unchecked")
    public Map<Long, Map<String, Object>> batchQuery(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    mallServiceBaseUrl + "/api/internal/product/batch-query",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("productIds", productIds), headers),
                    Map.class);

            Map<String, Object> body = resp.getBody();
            if (body != null && body.get("data") instanceof Map) {
                Map<String, Object> rawData = (Map<String, Object>) body.get("data");
                Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
                rawData.forEach((k, v) -> {
                    try {
                        result.put(Long.valueOf(k), (Map<String, Object>) v);
                    } catch (Exception ignored) {}
                });
                return result;
            }
        } catch (Exception e) {
            log.error("批量查询商品详情失败: {}", e.getMessage());
        }
        return Map.of();
    }
}
