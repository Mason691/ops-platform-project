package com.aip.ops.component;

import com.aip.ops.config.DispatchProperties;
import com.aip.sdk.client.PlatformInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 派工单公司代码解析。
 *
 * <p>优先级：业务配置（公司简称 → 代码）→ {@code sys_corp.org_code} → {@code C{corpId}}。
 * 组织信息经底座内部 API 获取，10 分钟缓存；解析失败时直接兜底 C{corpId}，
 * 不阻断派工单创建（编号仍唯一）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyCodeResolver {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final Pattern VALID_CODE = Pattern.compile("^[A-Za-z0-9]{1,10}$");

    private final PlatformInternalClient platformClient;
    private final DispatchProperties properties;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public String resolve(Long corpId) {
        String configured = configuredCode(shortNameOf(corpId));
        if (configured != null) return configured;
        String orgCode = normalize(orgCodeOf(corpId));
        if (orgCode != null) return orgCode;
        return "C" + corpId;
    }

    private String configuredCode(String shortName) {
        if (shortName == null || shortName.isBlank()) return null;
        String target = shortName.trim();
        for (DispatchProperties.CompanyCodeRule rule : properties.getCompanyCodeRules()) {
            if (rule != null && target.equals(rule.getShortName() == null
                    ? null : rule.getShortName().trim())) {
                return normalize(rule.getCode());
            }
        }
        return null;
    }

    private String shortNameOf(Long corpId) {
        Object value = corpInfo(corpId).get("shortName");
        return value instanceof String s ? s : null;
    }

    private String orgCodeOf(Long corpId) {
        Object value = corpInfo(corpId).get("orgCode");
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corpInfo(Long corpId) {
        CacheEntry entry = cache.get(corpId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.ts < CACHE_TTL_MS) {
            return entry.info;
        }
        Map<String, Object> info = Map.of();
        boolean failed = false;
        try {
            Map<String, Object> resp = platformClient.get("/api/internal/corp/" + corpId, null);
            Object data = resp == null ? null : resp.get("data");
            if (data instanceof Map<?, ?> map) {
                info = (Map<String, Object>) map;
            }
        } catch (Exception e) {
            failed = true;
            log.warn("派工单公司代码解析失败 corpId={}: {}", corpId, e.getMessage());
        }
        // 失败不缓存，避免平台短暂不可用时 10 分钟内持续生成兜底公司代码
        if (failed) {
            return entry != null ? entry.info : info; // 优先复用上一次成功的组织信息
        }
        cache.put(corpId, new CacheEntry(info, now));
        return info;
    }

    private static String normalize(String code) {
        if (code == null) return null;
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return VALID_CODE.matcher(trimmed).matches() ? trimmed : null;
    }

    private record CacheEntry(Map<String, Object> info, long ts) {
    }
}
