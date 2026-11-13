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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final int OUTBOUND_QUERY_BATCH = 200;

    /**
     * 批量查询业务订单是否已产生销售出库单（未删除，含 DRAFT）。
     * 覆盖派工自动出库、业务数据手工出库、多订单同收据合并制单。
     *
     * <p>SCM 不可达或返回异常时抛 {@link BusinessException}（fail-closed），
     * 调用方不得在无法校验时继续发起派工。</p>
     */
    public Set<Long> outboundOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return Set.of();
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        Set<Long> result = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i += OUTBOUND_QUERY_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + OUTBOUND_QUERY_BATCH, ids.size()));
            result.addAll(queryOutboundBatch(batch));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<Long> queryOutboundBatch(List<Long> orderIds) {
        String url = baseUrl + "/api/internal/sale-outbound/outbound-orders";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = Map.of("orderIds", orderIds);
        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientResponseException e) {
            log.warn("SCM 查询出库状态失败 status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("供应链服务不可用，暂无法校验销售出库状态");
        } catch (Exception e) {
            log.warn("SCM 查询出库状态异常: {}", e.getMessage());
            throw BusinessException.of("供应链服务不可用，暂无法校验销售出库状态");
        }
        if (resp == null) {
            throw BusinessException.of("供应链服务无响应，暂无法校验销售出库状态");
        }
        Object code = resp.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = resp.get("msg");
            throw BusinessException.of("查询销售出库状态失败："
                    + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
        }
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> map)) return Set.of();
        Object listObj = map.get("outboundOrderIds");
        if (!(listObj instanceof List<?> list)) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Number n) result.add(n.longValue());
        }
        return result;
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
