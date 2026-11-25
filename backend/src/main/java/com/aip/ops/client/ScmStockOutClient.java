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
            return parseResponse(resp, request.dispatchId());
        } catch (RestClientResponseException e) {
            log.warn("SCM 生成出库单失败 dispatchId={}, status={}, body={}",
                    request.dispatchId(), e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("生成销售出库单失败：" + extractError(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.warn("SCM 生成出库单异常 dispatchId={}: {}", request.dispatchId(), e.getMessage());
            throw BusinessException.of("供应链服务不可用，请稍后重试");
        }
    }

    /** 施工员接受派工：SCM 领用单 0 未审核 → 1 已审核（幂等）。 */
    public void dispatchAccepted(Long corpId, Long sourceDispatchId, Long workerUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corpId", corpId);
        body.put("sourceDispatchId", sourceDispatchId);
        body.put("workerUserId", workerUserId);
        postInternal("/api/internal/scm/material-pick/dispatch-accepted", body,
                "物料领用单状态同步失败");
    }

    /** 施工员拒绝派工：SCM 领用单逻辑删除并留痕（幂等）。 */
    public void dispatchRejected(Long corpId, Long sourceDispatchId, String reason,
                                 Long operatorUserId, String operatorName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corpId", corpId);
        body.put("sourceDispatchId", sourceDispatchId);
        body.put("reason", reason);
        body.put("operatorUserId", operatorUserId);
        body.put("operatorName", operatorName);
        postInternal("/api/internal/scm/material-pick/dispatch-rejected", body,
                "物料领用单删除失败");
    }

    private void postInternal(String path, Map<String, Object> body, String errorPrefix) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    baseUrl + path, new HttpEntity<>(body, headers), Map.class);
            Object code = resp == null ? null : resp.get("code");
            if (code != null && !"200".equals(String.valueOf(code))
                    && !Integer.valueOf(200).equals(code)) {
                throw BusinessException.of(errorPrefix + "："
                        + Objects.toString(resp.get("msg"), "未知错误"));
            }
        } catch (RestClientResponseException e) {
            log.warn("SCM 物料领用联动失败 path={}, status={}, body={}",
                    path, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of(errorPrefix + "：" + extractError(e.getResponseBodyAsString()));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("SCM 物料领用联动异常 path={}: {}", path, e.getMessage());
            throw BusinessException.of("供应链服务不可用，请稍后重试");
        }
    }

    /**
     * 拒绝接单后重新派工：只为新施工员生成物料领用单（销售出库单复用原有单据）。
     * SCM 按 (corp_id, source_dispatch_id=新 taskId) 幂等。
     */
    public MaterialPickResult createMaterialPick(ScmDispatchOutboundRequest request) {
        String url = baseUrl + "/api/internal/scm/material-pick/from-dispatch";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    url, new HttpEntity<>(request, headers), Map.class);
            Map<String, Object> data = responseData(resp);
            if (data == null || data.get("id") == null) {
                return null;
            }
            return new MaterialPickResult(((Number) data.get("id")).longValue(),
                    Objects.toString(data.get("billNo"), null));
        } catch (RestClientResponseException e) {
            log.warn("SCM 生成物料领用单失败 dispatchId={}, status={}, body={}",
                    request.dispatchId(), e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("生成物料领用单失败：" + extractError(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.warn("SCM 生成物料领用单异常 dispatchId={}: {}", request.dispatchId(), e.getMessage());
            throw BusinessException.of("供应链服务不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseData(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        Object code = resp.get("code");
        boolean ok = code == null || "200".equals(String.valueOf(code))
                || Integer.valueOf(200).equals(code);
        if (!ok) {
            String msg = Objects.toString(resp.get("msg"), "未知错误");
            throw BusinessException.of("生成物料领用单失败：" + msg);
        }
        Object data = resp.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    public record MaterialPickResult(Long id, String billNo) {
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
            log.warn("SCM 查询出库状态失败 orderIds={}, status={}, body={}",
                    orderIds, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("供应链服务不可用，暂无法校验销售出库状态");
        } catch (Exception e) {
            log.warn("SCM 查询出库状态异常 orderIds={}: {}", orderIds, e.getMessage());
            throw BusinessException.of("供应链服务不可用，暂无法校验销售出库状态");
        }
        if (resp == null) {
            log.error("SCM 查询出库状态无响应 orderIds={}", orderIds);
            throw BusinessException.of("供应链服务无响应，暂无法校验销售出库状态");
        }
        Object code = resp.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = resp.get("msg");
            // 必须记录 SCM 原始响应：否则只剩“系统内部错误”，无法定位供应链侧根因
            log.error("SCM 查询出库状态返回失败 orderIds={}, code={}, msg={}, resp={}",
                    orderIds, n.intValue(), msg, resp);
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
    private Result parseResponse(Map<String, Object> resp, Long dispatchId) {
        if (resp == null) {
            log.error("SCM 生成出库单无响应 dispatchId={}", dispatchId);
            throw BusinessException.of("生成销售出库单失败：供应链服务无响应");
        }
        Object code = resp.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = resp.get("msg");
            log.error("SCM 生成出库单返回失败 dispatchId={}, code={}, msg={}, resp={}",
                    dispatchId, n.intValue(), msg, resp);
            throw BusinessException.of("生成销售出库单失败："
                    + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
        }
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> map)) {
            log.error("SCM 生成出库单返回数据异常 dispatchId={}, resp={}", dispatchId, resp);
            throw BusinessException.of("生成销售出库单失败：返回数据异常");
        }
        Long id = map.get("id") instanceof Number n ? n.longValue() : null;
        String billNo = map.get("billNo") instanceof String s ? s : null;
        if (id == null || billNo == null || billNo.isBlank()) {
            log.error("SCM 生成出库单未返回单号 dispatchId={}, resp={}", dispatchId, resp);
            throw BusinessException.of("生成销售出库单失败：未返回单号");
        }
        Long materialPickId = map.get("materialPickId") instanceof Number n ? n.longValue() : null;
        String materialPickBillNo = map.get("materialPickBillNo") instanceof String s ? s : null;
        return new Result(id, billNo, materialPickId, materialPickBillNo);
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

    public record Result(Long id, String billNo, Long materialPickId, String materialPickBillNo) {
    }
}
