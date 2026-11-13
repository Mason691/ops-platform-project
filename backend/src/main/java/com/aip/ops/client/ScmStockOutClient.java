package com.aip.ops.client;

import com.aip.ops.dto.ScmDispatchOutboundRequest;
import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SCM 销售出库单内部客户端。
 *
 * <p>ops 只负责发起派工与接单，商品匹配、批次选择、客户/仓库默认值均属于 SCM 领域，
 * 因此这里只传派工单快照（品牌/品类/型号/数量/单价），由 SCM 内部接口完成出库单创建。
 * 调用使用 X-Internal-Token（与 SCM 回写 ops 的内部接口同一约定）。</p>
 */
@Slf4j
@Component
public class ScmStockOutClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public ScmStockOutClient(RestTemplate restTemplate,
                             @Value("${scm-service.base-url:http://localhost:8084}") String baseUrl,
                             @Value("${platform.internal-token}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
    }

    /**
     * 按派工单生成销售出库单（DRAFT/未审核）。SCM 侧按 dispatchId 幂等，
     * 重复调用返回同一张出库单，便于 ops 接单失败重试。
     */
    @SuppressWarnings("unchecked")
    public Result createFromDispatch(ScmDispatchOutboundRequest request) {
        String url = baseUrl + "/api/internal/sale-outbound/from-dispatch";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    url, new HttpEntity<>(request, headers), Map.class);
            return parseResponse(resp);
        } catch (RestClientResponseException e) {
            log.warn("SCM 生成出库单失败 dispatchId={}, status={}, body={}",
                    request.dispatchId(), e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("生成销售出库单失败：" + extractError(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.warn("SCM 生成出库单异常 dispatchId={}: {}", request.dispatchId(), e.getMessage());
            throw BusinessException.of("供应链服务不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private Result parseResponse(Map<String, Object> resp) {
        if (resp == null) {
            throw BusinessException.of("生成销售出库单失败：供应链服务无响应");
        }
        Object code = resp.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = resp.get("msg");
            throw BusinessException.of("生成销售出库单失败："
                    + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
        }
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> map)) {
            throw BusinessException.of("生成销售出库单失败：返回数据异常");
        }
        Long id = map.get("id") instanceof Number n ? n.longValue() : null;
        String billNo = map.get("billNo") instanceof String s ? s : null;
        if (id == null || billNo == null || billNo.isBlank()) {
            throw BusinessException.of("生成销售出库单失败：未返回单号");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("billNo", billNo);
        return new Result(id, billNo);
    }

    /** 从 SCM 的 R 响应体里提取可读错误（兼容 JSON 与纯文本）。 */
    private static String extractError(String body) {
        if (body == null || body.isBlank()) return "供应链服务返回空响应";
        try {
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            Object msg = map.get("msg");
            if (msg instanceof String s && !s.isBlank()) return s;
        } catch (Exception ignored) {
            // 非 JSON 响应直接截断展示
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    public record Result(Long id, String billNo) {
    }
}
