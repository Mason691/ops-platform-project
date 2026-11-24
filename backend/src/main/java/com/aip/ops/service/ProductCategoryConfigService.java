package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 品类配置读取（平台数据字典 {@code PRODUCT_CATEGORY_BRAND}）。
 *
 * <p>itemKey = 供应链品类名称，itemValue = 1/是 表示必须选择品牌，0/否 表示品牌可选。
 * 字典缺失时返回空映射，由调用方按“该品类是否存在带品牌商品”自动兜底，
 * 避免把“报警器”等无品牌品类硬编码在代码里。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCategoryConfigService {

    private static final String DICT_TYPE = "PRODUCT_CATEGORY_BRAND";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final PlatformInternalClient platformInternalClient;

    private volatile Map<String, Boolean> cache = Map.of();
    private volatile long expireAt;

    /** true=必须选择品牌，false=品牌可选；未配置返回 null。 */
    public Boolean brandRequired(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        return config().get(categoryName.trim());
    }

    public boolean isBrandOptional(String categoryName) {
        return Boolean.FALSE.equals(brandRequired(categoryName));
    }

    private Map<String, Boolean> config() {
        long now = System.currentTimeMillis();
        if (now < expireAt) return cache;
        synchronized (this) {
            if (now < expireAt) return cache;
            Map<String, Boolean> loaded = load();
            cache = loaded;
            expireAt = now + CACHE_TTL_MS;
            return loaded;
        }
    }

    private Map<String, Boolean> load() {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=" + DICT_TYPE, null);
            Object data = resp == null ? null : resp.get("data");
            if (!(data instanceof List<?> list)) return Map.of();
            Map<String, Boolean> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String name = text(row.get("itemKey"));
                String value = text(row.get("itemValue"));
                if (name.isEmpty() || value.isEmpty()) continue;
                result.put(name, parseRequired(value));
            }
            return result;
        } catch (Exception e) {
            log.warn("读取品类品牌配置字典失败 typeCode={}: {}", DICT_TYPE, e.getMessage());
            return Map.of();
        }
    }

    /** 兼容 1/0、是/否、true/false；未知值按“必须选择品牌”保守处理。 */
    private static boolean parseRequired(String value) {
        String lower = value.trim().toLowerCase();
        return switch (lower) {
            case "0", "否", "false", "no", "optional" -> false;
            default -> true;
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
