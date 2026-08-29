package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 替代 MerchantMapper 跨库查询的服务。
 * 通过底座平台内部 API 获取组织信息，不再直查 ai_platform.sys_corp。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformMerchantService {

    private final PlatformInternalClient platformInternalClient;

    /**
     * 批量查询组织名称（替代 MerchantMapper.selectCorpNamesByIds）。
     */
    @SuppressWarnings("unchecked")
    public Map<Long, String> getCorpNames(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        try {
            Map<String, Object> resp = platformInternalClient.post(
                    "/api/internal/corp/batch-names",
                    Map.of("ids", new ArrayList<>(ids)),
                    null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) return Map.of();
            Map<Long, String> result = new LinkedHashMap<>();
            data.forEach((k, v) -> {
                try { result.put(Long.valueOf(k), v.toString()); } catch (Exception ignored) {}
            });
            return result;
        } catch (Exception e) {
            log.error("批量查询组织名称失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 按角色查询组织列表（分页），替代 MerchantMapper.selectMerchants + countMerchants。
     * 调用平台 GET /api/internal/corp/list-by-role。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMerchantsByRole(
            int pageNum, int pageSize, String roleName, String keyword) {
        try {
            StringBuilder path = new StringBuilder(
                    String.format("/api/internal/corp/list-by-role?roleName=%s&pageNum=%d&pageSize=%d",
                            roleName, pageNum, pageSize));
            if (keyword != null && !keyword.isEmpty()) {
                path.append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"));
            }
            Map<String, Object> resp = platformInternalClient.get(path.toString(), null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("records") != null) {
                return (List<Map<String, Object>>) data.get("records");
            }
        } catch (Exception e) {
            log.error("按角色查询组织列表失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 按角色查询组织总数，替代 MerchantMapper.countMerchants。
     */
    public long countMerchantsByRole(String roleName, String keyword) {
        try {
            StringBuilder path = new StringBuilder(
                    String.format("/api/internal/corp/list-by-role?roleName=%s&pageNum=1&pageSize=1",
                            roleName));
            if (keyword != null && !keyword.isEmpty()) {
                path.append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"));
            }
            Map<String, Object> resp = platformInternalClient.get(path.toString(), null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("total") instanceof Number) {
                return ((Number) data.get("total")).longValue();
            }
        } catch (Exception e) {
            log.error("按角色统计组织数量失败: {}", e.getMessage());
        }
        return 0L;
    }

    /**
     * 查询所有配置了燃气公司ID的组织，建立 gas_company_id → corpId 映射。
     * 供燃具迁移时把外部公司标识解析为本地组织；平台不可达时返回空 Map（降级=全部未匹配，安全）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getCorpIdsByGasCompanyId() {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/with-gas-company-id", null);
            Object data = resp.get("data");
            if (!(data instanceof List<?> list)) return Map.of();
            Map<String, Long> result = new LinkedHashMap<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object gcid = m.get("gasCompanyId");
                Object id = m.get("id");
                if (gcid == null || id == null) continue;
                String key = gcid.toString().trim();
                if (key.isEmpty()) continue;
                try { result.put(key, ((Number) id).longValue()); } catch (Exception ignored) {}
            }
            return result;
        } catch (Exception e) {
            log.error("查询燃气公司ID组织映射失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 校验指定组织是否拥有指定角色，替代 MerchantMapper.checkMerchantRole。
     */
    public boolean hasRole(Long corpId, String roleName) {
        try {
            String path = String.format("/api/internal/corp/%d/check-role?roleName=%s",
                    corpId, java.net.URLEncoder.encode(roleName, "UTF-8"));
            Map<String, Object> resp = platformInternalClient.get(path, null);
            return resp.get("data") != null;
        } catch (Exception e) {
            log.error("校验组织角色失败: corpId={}, roleName={}", corpId, roleName, e);
            return false;
        }
    }
}
