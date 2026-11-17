package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务数据订单状态字典（business_order_status）。
 *
 * <p>所有中文名称来自平台数据字典，代码不硬编码；字典不可达时退回原始 key，
 * 保证列表/导出仍可用。</p>
 */
@Slf4j
@Service
public class BizStatusService {

    public static final String DICT_TYPE = "business_order_status";

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final PlatformInternalClient platformInternalClient;

    private volatile Map<String, String> cache = Map.of();
    private volatile long loadedAt = 0L;

    public BizStatusService(PlatformInternalClient platformInternalClient) {
        this.platformInternalClient = platformInternalClient;
    }

    /** key → 中文名（按字典排序）。 */
    public Map<String, String> statuses() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - loadedAt < CACHE_TTL_MS) {
            return cache;
        }
        synchronized (this) {
            if (!cache.isEmpty() && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS) {
                return cache;
            }
            Map<String, String> loaded = load();
            if (!loaded.isEmpty()) {
                cache = loaded;
                loadedAt = System.currentTimeMillis();
            }
            return cache;
        }
    }

    public String label(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return statuses().getOrDefault(key, key);
    }

    /** 下拉选项：itemKey/itemValue 结构与平台字典接口一致。 */
    public List<Map<String, String>> options() {
        List<Map<String, String>> result = new ArrayList<>();
        statuses().forEach((key, value) -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("itemKey", key);
            item.put("itemValue", value);
            result.add(item);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> load() {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=" + DICT_TYPE, null);
            Object data = resp == null ? null : resp.get("data");
            if (!(data instanceof List<?> list)) {
                return Map.of();
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                Object key = row.get("itemKey");
                Object value = row.get("itemValue");
                if (key != null && value != null) {
                    map.put(String.valueOf(key), String.valueOf(value));
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("加载业务状态字典失败（{}）：{}", DICT_TYPE, e.getMessage());
            return Map.of();
        }
    }
}
