package com.aip.ops.client;

import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应链采购订单内部客户端。
 *
 * <p>业务订单以旧换新保存后，SCM 自动创建采购订单：满足风控时直接审核并推送金蝶，
 * 否则回退未审核草稿。SCM 侧以 {@code (corp_id, data_source, source_order_id)}
 * 唯一约束保证幂等；本客户端失败时抛异常，由调用方把订单标记 FAILED 并提示，不静默吞掉。</p>
 *
 * <p>超时：SCM 自动审核后同步推送金蝶（含 429/5xx 退避重试），通用 10s 读超时可能
 * 误判失败，故本客户端使用 60s 读超时；重试仍幂等（重复调用返回已有单据）。</p>
 */
@Slf4j
@Component
public class ScmPurchaseOrderClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public ScmPurchaseOrderClient(
            @Value("${scm-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${platform.internal-token}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
    }

    /** 创建以旧换新采购订单（幂等：同一业务订单重复调用返回已有单据）。 */
    @SuppressWarnings("unchecked")
    public TradeInPurchaseResult createTradeInPurchaseOrder(TradeInPurchaseRequest request) {
        if (request == null || request.corpId() == null || request.businessOrderId() == null) {
            throw BusinessException.of("旧品采购单参数不完整");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("corpId", request.corpId());
            body.put("businessOrderId", request.businessOrderId());
            body.put("businessOrderNo", request.businessOrderNo());
            // 触发员工：SCM 按此重建审计上下文，操作日志可追溯到人
            body.put("operatorUserId", request.operatorUserId());
            body.put("operatorName", request.operatorName());
            body.put("items", request.items());
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/api/internal/scm/pur-order/trade-in",
                    new HttpEntity<>(body, headers), Map.class);
            return parseResult(resp.getBody());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("创建以旧换新采购单失败 orderId={}: {}", request.businessOrderId(), e.getMessage());
            throw BusinessException.of("旧品采购单创建失败："
                    + (e.getMessage() == null ? "供应链服务不可用" : e.getMessage()));
        }
    }

    /**
     * 查询采购订单当前状态（入库状态用于施工完成提醒）。
     * 供应链不可达返回 null，调用方展示“状态待同步”，不阻断订单详情。
     */
    @SuppressWarnings("unchecked")
    public TradeInPurchaseStatus status(Long corpId, Long purchaseOrderId) {
        if (corpId == null || purchaseOrderId == null) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/internal/scm/pur-order/" + purchaseOrderId
                            + "/status?corpId=" + corpId,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null || !(body.get("code") instanceof Number n) || n.intValue() != 200) {
                return null;
            }
            if (!(body.get("data") instanceof Map<?, ?> data)) return null;
            return new TradeInPurchaseStatus(
                    longVal(data.get("id")), str(data.get("billNo")),
                    str(data.get("approvalStatus")), str(data.get("auditStatus")),
                    str(data.get("execStatus")), str(data.get("inboundStatus")),
                    str(data.get("sourceType")), dateTime(data.get("autoApprovedAt")),
                    str(data.get("autoApproveTrigger")), str(data.get("autoApproveError")),
                    str(data.get("syncStatus")), str(data.get("pushError")),
                    str(data.get("jdyBillno")));
        } catch (Exception e) {
            log.warn("查询以旧换新采购单状态失败 id={}: {}", purchaseOrderId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private TradeInPurchaseResult parseResult(Map<String, Object> body) {
        if (body == null || !(body.get("code") instanceof Number n) || n.intValue() != 200) {
            Object msg = body == null ? null : body.get("msg");
            throw BusinessException.of("旧品采购单创建失败："
                    + (msg == null ? "供应链返回异常" : String.valueOf(msg)));
        }
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            throw BusinessException.of("旧品采购单创建失败：供应链返回数据异常");
        }
        return new TradeInPurchaseResult(
                longVal(data.get("id")), str(data.get("billNo")),
                str(data.get("auditStatus")), str(data.get("approvalStatus")),
                str(data.get("supplierNumber")), str(data.get("warehouseNumber")),
                Boolean.TRUE.equals(data.get("duplicate")));
    }

    /**
     * 业务订单财务核对 → 触发 SCM 采购单自动审核并推送金蝶。
     * 失败不抛出（财务核对不可因金蝶/供应链抖动而失败），返回 error 供详情展示。
     */
    @SuppressWarnings("unchecked")
    public FinanceResult financeVerified(Long corpId, Long businessOrderId,
                                         Long operatorUserId, String operatorName) {
        if (corpId == null || businessOrderId == null) {
            return new FinanceResult(false, false, "缺少组织或业务订单信息", null);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("corpId", corpId);
            body.put("businessOrderId", businessOrderId);
            body.put("operatorUserId", operatorUserId);
            body.put("operatorName", operatorName);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/api/internal/scm/pur-order/finance-verified",
                    new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            if (respBody == null || !(respBody.get("code") instanceof Number n)
                    || n.intValue() != 200) {
                Object msg = respBody == null ? null : respBody.get("msg");
                return new FinanceResult(false, false,
                        msg == null ? "供应链返回异常" : String.valueOf(msg), null);
            }
            if (!(respBody.get("data") instanceof Map<?, ?> data)) {
                return new FinanceResult(false, false, "供应链返回数据异常", null);
            }
            return new FinanceResult(Boolean.TRUE.equals(data.get("approved")),
                    Boolean.TRUE.equals(data.get("skipped")), str(data.get("error")),
                    str(data.get("billNo")));
        } catch (Exception e) {
            log.warn("财务核对触发采购单自动审核失败 corpId={}, orderId={}: {}",
                    corpId, businessOrderId, e.getMessage());
            return new FinanceResult(false, false,
                    e.getMessage() == null ? "供应链服务不可用" : e.getMessage(), null);
        }
    }

    /** 业务订单撤销财务核对 → 采购单联动反审（失败只记日志，不阻塞业务）。 */
    public void financeReverted(Long corpId, Long businessOrderId) {
        if (corpId == null || businessOrderId == null) return;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("corpId", corpId);
            body.put("businessOrderId", businessOrderId);
            restTemplate.postForEntity(
                    baseUrl + "/api/internal/scm/pur-order/finance-reverted",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.warn("撤销财务核对联动采购单反审失败 corpId={}, orderId={}: {}",
                    corpId, businessOrderId, e.getMessage());
        }
    }

    private static Long longVal(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime dateTime(Object value) {
        String text = str(value);
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    public record TradeInPurchaseRequest(Long corpId, Long businessOrderId, String businessOrderNo,
                                         Long operatorUserId, String operatorName,
                                         List<TradeInItem> items) {
    }

    public record TradeInItem(Long tradeInId, Long productId, String materialNumber,
                              String materialName, String brand, String model, String category,
                              Integer quantity, BigDecimal taxPrice, String unit, String remark,
                              Boolean isManual) {
    }

    public record TradeInPurchaseResult(Long orderId, String billNo, String auditStatus,
                                        String approvalStatus, String supplierNumber,
                                        String warehouseNumber, boolean duplicate) {
    }

    public record TradeInPurchaseStatus(Long orderId, String billNo, String approvalStatus,
                                        String auditStatus, String execStatus, String inboundStatus,
                                        String sourceType, LocalDateTime autoApprovedAt,
                                        String autoApproveTrigger, String autoApproveError,
                                        String syncStatus, String pushError, String jdyBillno) {
    }

    public record FinanceResult(boolean approved, boolean skipped, String error, String billNo) {
    }
}
