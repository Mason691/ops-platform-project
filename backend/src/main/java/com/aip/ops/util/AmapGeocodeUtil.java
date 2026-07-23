package com.aip.ops.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 高德地图地理编码工具。
 * 根据地址文本获取经纬度坐标。
 */
@Slf4j
@Component
public class AmapGeocodeUtil {

    private final RestTemplate restTemplate;
    private final String apiKey;

    private static final String GEOCODE_URL =
            "https://restapi.amap.com/v3/geocode/geo?key={key}&address={address}";

    public AmapGeocodeUtil(RestTemplate restTemplate,
                           @Value("${amap.key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
    }

    /**
     * 根据地址获取经纬度。
     * @param address 结构化地址（如 "广西百色市德保县幸福家园"）
     * @return [lng, lat] 或 null（失败时）
     */
    public double[] geocode(String address) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(
                    GEOCODE_URL, Map.class, apiKey, address);
            if (resp == null || !"1".equals(String.valueOf(resp.get("status")))) {
                log.warn("Amap geocode failed for '{}': status={}", address,
                        resp != null ? resp.get("status") : "null");
                return null;
            }
            var geocodes = resp.get("geocodes");
            if (!(geocodes instanceof java.util.List) || ((java.util.List<?>) geocodes).isEmpty()) {
                log.warn("Amap geocode no results for '{}'", address);
                return null;
            }
            var first = ((java.util.List<?>) geocodes).get(0);
            if (!(first instanceof Map)) return null;
            String location = (String) ((Map<?, ?>) first).get("location");
            if (location == null || !location.contains(",")) return null;
            String[] parts = location.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            log.warn("Amap geocode error for '{}': {}", address, e.getMessage());
            return null;
        }
    }
}
