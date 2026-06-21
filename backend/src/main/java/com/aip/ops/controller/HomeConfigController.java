package com.aip.ops.controller;

import com.aip.ops.entity.OpsHomeConfig;
import com.aip.ops.service.HomeConfigService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 首页配置管理 Controller — 管理后台 CRUD。
 * 所有接口需要 JWT 认证（由 SecurityConfig 保护 /api/ops/admin/**）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin")
@RequiredArgsConstructor
public class HomeConfigController {

    private final HomeConfigService homeConfigService;

    private Long currentOperatorId() {
        return SecurityUtils.getCurrentCorpId();
    }

    /**
     * 获取首页配置列表（按类型筛选）。
     */
    @GetMapping("/home-configs")
    public R<?> list(@RequestParam(required = false) String configType,
                     @RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "100") int pageSize) {
        return R.ok(homeConfigService.pageByType(currentOperatorId(), configType, pageNum, pageSize));
    }

    /**
     * 新增首页配置。
     */
    @PostMapping("/home-configs")
    public R<?> create(@RequestBody OpsHomeConfig config) {
        config.setOperatorId(currentOperatorId());
        Long id = homeConfigService.create(config);
        return R.ok(Map.of("id", id));
    }

    /**
     * 批量更新排序 — 必须在 /{id} 路由之前声明，避免 "sort" 被当作路径变量。
     */
    @PutMapping("/home-configs/sort")
    public R<?> batchSort(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) {
            return R.fail("排序列表不能为空");
        }
        homeConfigService.batchUpdateSort(items);
        return R.ok();
    }

    /**
     * 修改首页配置（部分更新）。
     */
    @PutMapping("/home-configs/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsHomeConfig config) {
        homeConfigService.update(id, config);
        return R.ok();
    }

    /**
     * 删除首页配置。
     */
    @DeleteMapping("/home-configs/{id}")
    public R<?> delete(@PathVariable Long id) {
        homeConfigService.delete(id);
        return R.ok();
    }

    /**
     * 获取可关联的活动视频列表（type='activity' 且有 video_url）。
     */
    @GetMapping("/activities/available-videos")
    public R<?> availableVideos() {
        return R.ok(homeConfigService.getAvailableVideos(currentOperatorId()));
    }

    /**
     * 获取可推荐商品列表（绑定销售商的上架商品）。
     */
    @GetMapping("/products/recommendable")
    public R<?> recommendableProducts() {
        return R.ok(homeConfigService.getRecommendableProducts(currentOperatorId()));
    }
}
