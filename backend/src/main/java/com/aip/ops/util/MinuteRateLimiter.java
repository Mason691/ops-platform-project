package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 key（用户/IP）的分钟级限流，用于保护高德逆地理编码配额。
 * 仅单实例内存计数；多实例部署时需替换为 Redis（当前 ops 为单实例）。
 */
public final class MinuteRateLimiter {

    private final int maxPerMinute;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    public MinuteRateLimiter(int maxPerMinute) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    public void check(Long key) {
        if (key == null) return;
        long minute = System.currentTimeMillis() / 60_000L;
        Window window = windows.compute(key, (k, old) ->
                old == null || old.minute != minute
                        ? new Window(minute, 1)
                        : new Window(minute, old.count + 1));
        if (window.count > maxPerMinute) {
            throw BusinessException.of(429, "定位请求过于频繁，请稍后重试或手动填写");
        }
        if (windows.size() > 1000) {
            windows.entrySet().removeIf(e -> e.getValue().minute < minute);
        }
    }

    private record Window(long minute, int count) {
    }
}
