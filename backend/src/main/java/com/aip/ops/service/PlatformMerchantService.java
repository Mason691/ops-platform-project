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
 *
 * 注意：selectMerchants / countMerchants / checkMerchantRole 三个按角色筛选的方法
 * 依赖平台提供角色查询接口，当前使用平台 API 暂不完全支持，
 * 标记为技术债务，待平台暴露 GET /api/internal/corp/list-by-role 后迁移。
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
     * 按角色查询组织列表（分页）。
     * TODO: 待平台实现 GET /api/internal/corp/list-by-role 后替换现有 MerchantMapper.selectMerchants。
     */
    public List<Map<String, Object>> getMerchantsByRole(
            int pageNum, int pageSize, String roleName, String keyword) {
        try {
            String path = String.format(
                    "/api/internal/corp/list-by-role?pageNum=%d&pageSize=%d&roleName=%s%s",
                    pageNum, pageSize, roleName,
                    keyword != null && !keyword.isEmpty() ? "&keyword=" + keyword : "");
            Map<String, Object> resp = platformInternalClient.get(path, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("records") != null) {
                return (List<Map<String, Object>>) data.get("records");
            }
        } catch (Exception e) {
            log.error("按角色查询组织列表失败: {}", e.getMessage());
        }
        return List.of();
    }
}
