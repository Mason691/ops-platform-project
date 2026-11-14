package com.aip.ops.client;

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
 * 平台工作流组件客户端。
 *
 * <p>ops 侧只负责「按订单归属组织发起流程」；审批推进、待办、公众号消息
 * 全部由平台工作流组件承担。</p>
 */
@Slf4j
@Component
public class WorkflowPlatformClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public WorkflowPlatformClient(RestTemplate restTemplate,
                                  @Value("${platform.base-url}") String baseUrl,
                                  @Value("${platform.internal-token}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
    }

    /** 发起流程实例，返回平台实例 ID。 */
    @SuppressWarnings("unchecked")
    public Long start(StartRequest request) {
        String url = baseUrl + "/api/internal/wf/instance/start";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionCode", request.definitionCode());
        body.put("businessType", request.businessType());
        body.put("businessId", request.businessId());
        body.put("businessSummary", request.businessSummary());
        body.put("businessAddress", request.businessAddress());
        body.put("requestId", request.requestId());
        body.put("corpId", request.corpId());
        body.put("initiatorUserId", request.initiatorUserId());
        body.put("initiatorName", request.initiatorName());
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            if (resp == null) {
                throw BusinessException.of("工作流服务无响应");
            }
            Object code = resp.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                Object msg = resp.get("msg");
                throw BusinessException.of("发起工作流失败："
                        + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
            }
            Object data = resp.get("data");
            if (!(data instanceof Map<?, ?> instance)) {
                throw BusinessException.of("发起工作流失败：返回数据异常");
            }
            Object id = instance.get("id");
            if (!(id instanceof Number n)) {
                throw BusinessException.of("发起工作流失败：未返回实例ID");
            }
            return n.longValue();
        } catch (RestClientResponseException e) {
            log.warn("发起工作流失败 status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("发起工作流异常: {}", e.getMessage());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        }
    }

    public record StartRequest(String definitionCode, String businessType, String businessId,
                               String businessSummary, String businessAddress, String requestId,
                               Long corpId, Long initiatorUserId, String initiatorName) {
    }
}
