package com.aip.ops.client;

import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应链采购订单内部客户端。
 *
 * <p>业务订单以旧换新保存后，自动创建一张未审核采购订单。SCM 侧以
 * {@code (corp_id, data_source, source_order_id)} 唯一约束保证幂等；
 * 本客户端失败时抛异常，由调用方把订单标记 FAILED 并提示，不静默吞掉。</p>
 */
@Slf4j
@Component
public class ScmPurchaseOrderClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public ScmPurchaseOrderClient(RestTemplate restTemplate,
                                  @Value("${scm-service.base-url:http://localhost:8084}") String baseUrl,
                                  @Value("${platform.internal-token}") String internalToken) {
        this.restTemplate = restTemplate;
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
                    str(data.get("execStatus")), str(data.get("inboundStatus")));
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
                str(data.get("inboundStatus")), Boolean.TRUE.equals(data.get("duplicate")));
    }

    private static Long longVal(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record TradeInPurchaseRequest(Long corpId, Long businessOrderId, String businessOrderNo,
                                         List<TradeInItem> items) {
    }

    public record TradeInItem(Long tradeInId, Long productId, String materialNumber,
                              String materialName, String brand, String model, String category,
                              Integer quantity, BigDecimal taxPrice, String unit, String remark,
                              Boolean isManual) {
    }

    public record TradeInPurchaseResult(Long orderId, String billNo, String auditStatus,
                                        String approvalStatus, String inboundStatus,
                                        boolean duplicate) {
    }

    public record TradeInPurchaseStatus(Long orderId, String billNo, String approvalStatus,
                                        String auditStatus, String execStatus, String inboundStatus) {
    }
}
