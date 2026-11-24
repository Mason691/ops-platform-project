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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * 供应链库存查询代理。
 *
 * <p>员工制单/部长审核需要查看本组织及其他城市合伙人库存，ops 不直连 SCM 库，
 * 携带当前员工 JWT 调用 scm-mehome-service 的只读接口
 * {@code GET /api/scm/inventory/model-stock}。SCM 按 JWT corp_id 解析所属运营商，
 * 城市合伙人只能看到同一运营商体系内的库存。</p>
 *
 * <p>库存仅作参考，不参与制单校验；SCM 不可达时前端降级为“库存暂不可用”，
 * 不阻断制单/审核。</p>
 */
@Slf4j
@Component
public class ScmInventoryClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ScmInventoryClient(RestTemplate restTemplate,
                              @Value("${scm-service.base-url:http://localhost:8084}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** materialId 优先；订单详情没有商品ID时用品牌/品类/型号定位。 */
    public Map<String, Object> modelStock(Long materialId, String brand, String category,
                                          String model) {
        String auth = currentBearerToken();
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/scm/inventory/model-stock");
        if (materialId != null && materialId > 0) {
            builder.queryParam("materialId", materialId);
        } else {
            addParam(builder, "brand", brand);
            addParam(builder, "category", category);
            addParam(builder, "model", model);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", auth);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        try {
            URI uri = builder.build().encode().toUri();
            ResponseEntity<Map> resp = restTemplate.exchange(uri,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<?, ?> body = resp.getBody();
            Object code = body == null ? null : body.get("code");
            Object data = body == null ? null : body.get("data");
            if (code instanceof Number n && n.intValue() == 200 && data instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            }
            Object msgValue = body == null ? null : body.get("msg");
            String msg = msgValue == null ? "库存查询失败" : String.valueOf(msgValue);
            throw BusinessException.of(msg);
        } catch (BusinessException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                throw BusinessException.of("登录已过期，请重新登录");
            }
            log.warn("SCM 型号库存查询失败 status={}: {}", e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw BusinessException.of("库存服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.warn("SCM 型号库存查询失败: {}", e.getMessage());
            throw BusinessException.of("库存服务暂不可用，请稍后重试");
        }
    }

    private static void addParam(UriComponentsBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(name, value.trim());
        }
    }

    private String currentBearerToken() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            throw BusinessException.of("登录已过期，请重新登录");
        }
        String header = attrs.getRequest().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw BusinessException.of("登录已过期，请重新登录");
        }
        return header;
    }
}
