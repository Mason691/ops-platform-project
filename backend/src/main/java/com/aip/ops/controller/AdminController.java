package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.IGsProductService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 运营商管理后台 — 绑定销售商、商品管理、分类管理。
 *
 * 销售商来源：底座平台 sys_corp 表中，在 sys_corp_role 中拥有 role_name='销售商' 的组织。
 * 商品数据：通过 gs_product（映射表）JOIN mall_product 获取完整商品信息。
 */
@RestController
@RequestMapping("/api/ops/admin")
public class AdminController {

    private final OperatorMerchantMapper omMapper;
    private final IGsProductService productService;
    private final ProductCategoryMapper categoryMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantMiniappConfigMapper miniappConfigMapper;
    private final String merchantRoleName;

    public AdminController(OperatorMerchantMapper omMapper,
                           IGsProductService productService,
                           ProductCategoryMapper categoryMapper,
                           MerchantMapper merchantMapper,
                           MerchantMiniappConfigMapper miniappConfigMapper,
                           @Value("${platform.merchant-role-name:销售商}") String merchantRoleName) {
        this.omMapper = omMapper;
        this.productService = productService;
        this.categoryMapper = categoryMapper;
        this.merchantMapper = merchantMapper;
        this.miniappConfigMapper = miniappConfigMapper;
        this.merchantRoleName = merchantRoleName;
    }

    /** 当前用户ID（用于个人身份） */
    private Long currentUserId() { return SecurityUtils.getCurrentUserId(); }

    /** 当前运营商组织ID（corp_id），用于商品/销售商等运营操作 */
    private Long currentOperatorId() { return SecurityUtils.getCurrentCorpId(); }

    // ═══ 绑定销售商 ═══

    /**
     * 获取销售商列表（含已绑定状态）。
     * 销售商 = 底座平台中拥有"销售商"企业角色的组织（sys_corp × sys_corp_role）。
     */
    @GetMapping("/merchants")
    public R<?> merchantList(@RequestParam(defaultValue="1") int pageNum,
                             @RequestParam(defaultValue="20") int pageSize,
                             @RequestParam(required=false) String keyword) {
        Long operatorId = currentOperatorId();

        Set<Long> bound = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());

        List<Map<String,Object>> merchants = merchantMapper.selectMerchants(
                (pageNum - 1) * pageSize, pageSize, merchantRoleName, keyword);

        merchants.forEach(m -> {
            Long mid = Long.valueOf(m.get("id").toString());
            m.put("bound", bound.contains(mid));
        });

        return R.ok(merchants);
    }

    /** 批量绑定销售商 */
    @PostMapping("/bind-merchants")
    @Transactional
    public R<?> bindMerchants(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("merchantIds");
        if (ids == null || ids.isEmpty()) return R.fail("请选择销售商");

        int count = 0;
        for (Integer id : ids) {
            Long merchantId = Long.valueOf(id);
            // 校验该组织确实拥有销售商角色
            if (merchantMapper.checkMerchantRole(merchantId, merchantRoleName) == null) {
                throw new IllegalArgumentException("组织ID " + merchantId + " 不是销售商，无法绑定");
            }
            Long exist = omMapper.selectCount(new LambdaQueryWrapper<OperatorMerchant>()
                    .eq(OperatorMerchant::getOperatorId, operatorId)
                    .eq(OperatorMerchant::getMerchantId, merchantId)
                    .eq(OperatorMerchant::getStatus, 1));
            if (exist > 0) continue;
            OperatorMerchant om = new OperatorMerchant();
            om.setOperatorId(operatorId); om.setMerchantId(merchantId); om.setStatus(1);
            omMapper.insert(om);
            count++;
        }
        return R.ok(Map.of("boundCount", count));
    }

    /** 解绑单个销售商 */
    @DeleteMapping("/bind-merchants/{merchantId}")
    public R<?> unbind(@PathVariable Long merchantId) {
        Long operatorId = currentOperatorId();
        // 双重校验：该组织确实是销售商
        if (merchantMapper.checkMerchantRole(merchantId, merchantRoleName) == null) {
            throw new IllegalArgumentException("组织ID " + merchantId + " 不是销售商");
        }
        OperatorMerchant om = omMapper.selectOne(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getMerchantId, merchantId)
                .eq(OperatorMerchant::getStatus, 1));
        if (om == null) return R.fail("未找到绑定关系");
        om.setStatus(0);
        omMapper.updateById(om);
        return R.ok();
    }

    // ═══ 小程序跳转配置 ═══

    /**
     * 获取指定销售商的小程序跳转配置。
     * 仅允许当前运营商已绑定的销售商。
     */
    @GetMapping("/merchant/{merchantId}/miniapp-config")
    public R<?> getMiniappConfig(@PathVariable Long merchantId) {
        Long operatorId = currentOperatorId();
        assertMerchantBound(operatorId, merchantId);
        MerchantMiniappConfig cfg = miniappConfigMapper.selectOne(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .eq(MerchantMiniappConfig::getMerchantId, merchantId));
        return R.ok(cfg != null ? cfg : new MerchantMiniappConfig());
    }

    /**
     * 新增或更新指定销售商的小程序跳转配置（upsert）。
     * 仅允许当前运营商已绑定的销售商。
     */
    @PutMapping("/merchant/{merchantId}/miniapp-config")
    @Transactional
    public R<?> saveMiniappConfig(@PathVariable Long merchantId,
                                   @RequestBody MerchantMiniappConfig body) {
        Long operatorId = currentOperatorId();
        assertMerchantBound(operatorId, merchantId);

        MerchantMiniappConfig existing = miniappConfigMapper.selectOne(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .eq(MerchantMiniappConfig::getMerchantId, merchantId));

        if (existing != null) {
            existing.setAppId(body.getAppId());
            existing.setPathTemplate(body.getPathTemplate());
            existing.setEnvVersion(body.getEnvVersion());
            existing.setRemark(body.getRemark());
            miniappConfigMapper.updateById(existing);
        } else {
            body.setMerchantId(merchantId);
            body.setId(null);
            miniappConfigMapper.insert(body);
        }
        return R.ok();
    }

    /** 校验当前运营商是否已绑定指定销售商，未绑定则抛出权限异常 */
    private void assertMerchantBound(Long operatorId, Long merchantId) {
        Long count = omMapper.selectCount(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getMerchantId, merchantId)
                .eq(OperatorMerchant::getStatus, 1));
        if (count == null || count == 0) {
            throw new IllegalArgumentException("无权操作：当前运营商未绑定该销售商");
        }
    }

    // ═══ 商品展示管理 ═══

    /**
     * 获取当前运营商的商品展示列表。
     * 通过 gs_product 映射表 JOIN mall_product 获取完整商品信息。
     */
    @GetMapping("/products")
    public R<?> products(@RequestParam(defaultValue="1") int pageNum,
                         @RequestParam(defaultValue="10") int pageSize,
                         @RequestParam(required=false) Long merchantId,
                         @RequestParam(required=false) Long categoryId,
                         @RequestParam(required=false) Integer shelfStatus,
                         @RequestParam(required=false) String keyword) {
        Long operatorId = currentOperatorId();
        return R.ok(productService.adminProductPage(
                operatorId, pageNum, pageSize, shelfStatus, categoryId, merchantId, keyword));
    }

    /** 单个商品上下架 */
    @PutMapping("/products/{id}/shelf-status")
    public R<?> toggleShelf(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        productService.updateShelfStatus(id, shelfStatus);
        return R.ok();
    }

    /** 批量上下架 */
    @PostMapping("/products/batch-shelf")
    public R<?> batchShelf(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdateShelfStatus(longIds, shelfStatus, operatorId);
        return R.ok(Map.of("count", count));
    }

    /** 批量设置分类 */
    @PutMapping("/products/batch-category")
    public R<?> batchCategory(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Long categoryId = Long.valueOf(body.getOrDefault("categoryId", 0).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdateCategory(longIds, categoryId, operatorId);
        return R.ok(Map.of("count", count));
    }

    /** 设置单个商品积分配额 */
    @PutMapping("/products/{id}/points-config")
    public R<?> updatePointsConfig(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer points = body.get("points") != null ? Integer.valueOf(body.get("points").toString()) : 0;
        java.math.BigDecimal exchangeAmount = body.get("exchangeAmount") != null
                ? new java.math.BigDecimal(body.get("exchangeAmount").toString()) : java.math.BigDecimal.ZERO;
        productService.updatePointsConfig(id, points, exchangeAmount, operatorId);
        return R.ok();
    }

    /** 批量设置积分配额 */
    @PostMapping("/products/batch-points-config")
    public R<?> batchPointsConfig(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer points = body.get("points") != null ? Integer.valueOf(body.get("points").toString()) : 0;
        java.math.BigDecimal exchangeAmount = body.get("exchangeAmount") != null
                ? new java.math.BigDecimal(body.get("exchangeAmount").toString()) : java.math.BigDecimal.ZERO;
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdatePointsConfig(longIds, points, exchangeAmount, operatorId);
        return R.ok(Map.of("count", count));
    }

    // ═══ 商品分类 CRUD ═══

    @GetMapping("/product-categories")
    public R<?> categories() {
        List<ProductCategory> list = categoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>().orderByAsc(ProductCategory::getSortOrder));
        List<Map<String,Object>> roots = new ArrayList<>();
        Map<Long,List<ProductCategory>> childrenMap = new LinkedHashMap<>();
        for (ProductCategory c : list) {
            if (c.getParentId() == null || c.getParentId() == 0)
                roots.add(catToMap(c));
            else
                childrenMap.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        for (Map<String,Object> root : roots) {
            Long rid = Long.valueOf(root.get("id").toString());
            List<ProductCategory> kids = childrenMap.getOrDefault(rid, Collections.emptyList());
            root.put("children", kids.stream().map(this::catToMap).collect(Collectors.toList()));
        }
        return R.ok(roots);
    }

    @PostMapping("/product-categories")
    public R<?> createCategory(@RequestBody ProductCategory cat) { categoryMapper.insert(cat); return R.ok(); }

    @PutMapping("/product-categories/{id}")
    public R<?> updateCategory(@PathVariable Long id, @RequestBody ProductCategory cat) {
        cat.setId(id); categoryMapper.updateById(cat); return R.ok();
    }

    @DeleteMapping("/product-categories/{id}")
    public R<?> deleteCategory(@PathVariable Long id) { categoryMapper.deleteById(id); return R.ok(); }

    private Map<String,Object> catToMap(ProductCategory c) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("name", c.getName());
        m.put("parentId", c.getParentId()); m.put("sortOrder", c.getSortOrder());
        return m;
    }
}
