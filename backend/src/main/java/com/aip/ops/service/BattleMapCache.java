package com.aip.ops.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 作战地图只读接口的进程内缓存（30min TTL）。
 * 供 Controller 读取聚合结果、EtlService 在刷新后按组织清除。
 * 单实例内存缓存——与项目现有 CaptchaService 一致，多实例部署需替换为分布式缓存。
 */
@Component
public class BattleMapCache {

    private static final long TTL_MS = 30 * 60 * 1000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(Object data, long ts) {}

    /** 组织维度缓存 key，segment 区分不同接口（sum/comm/cat 等）。 */
    public String key(Long orgId, String segment) {
        return "ops_dashboard:" + (orgId != null ? orgId : 0) + ":" + segment;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String k) {
        Entry e = store.get(k);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.ts() < TTL_MS) return (T) e.data();
        store.remove(k);
        return null;
    }

    public void set(String k, Object v) {
        store.put(k, new Entry(v, System.currentTimeMillis()));
    }

    /** 清除某组织的全部缓存段（ETL 刷新后调用）。 */
    public void clearOrg(Long orgId) {
        String prefix = "ops_dashboard:" + (orgId != null ? orgId : 0) + ":";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
