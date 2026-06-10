package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 运营商管理后台 — 绑定销售商、商品管理、分类管理
 */
@RestController
@RequestMapping("/api/ops/admin")
@RequiredArgsConstructor
public class AdminController {

    private final OperatorMerchantMapper omMapper;
    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final GsUserMapper userMapper;

    private Long currentUserId() { return SecurityUtils.getCurrentUserId(); }

    // ═══ 绑定销售商 ═══

    /** 获取销售商列表（含已绑定状态） */
    @GetMapping("/merchants")
    public R<?> merchantList(@RequestParam(defaultValue="1") int pageNum,
                             @RequestParam(defaultValue="20") int pageSize,
                             @RequestParam(required=false) String keyword) {
        Long operatorId = currentUserId();

        // 已绑定的 merchant 集合
        Set<Long> bound = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());

        // 从已有的 mall_product 中按 merchant_id 汇总 + mall_station_master
        // 简化实现：直接用 mall_station_master 作为销售商来源（需要从 mall_db 库查询）
        // 由于本平台和 mall 共享 mall_db，可直接跨表查询
        List<Map<String,Object>> merchants = userMapper.selectJoinedMerchants(
                (pageNum - 1) * pageSize, pageSize, keyword);

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
        Long operatorId = currentUserId();
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("merchantIds");
        if (ids == null || ids.isEmpty()) return R.fail("请选择销售商");

        int count = 0;
        for (Integer id : ids) {
            Long merchantId = Long.valueOf(id);
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
        Long operatorId = currentUserId();
        OperatorMerchant om = omMapper.selectOne(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getMerchantId, merchantId)
                .eq(OperatorMerchant::getStatus, 1));
        if (om == null) return R.fail("未找到绑定关系");
        om.setStatus(0);
        omMapper.updateById(om);
        return R.ok();
    }

    // ═══ 商品展示管理 ═══

    @GetMapping("/products")
    public R<?> products(@RequestParam(defaultValue="1") int pageNum,
                         @RequestParam(defaultValue="10") int pageSize,
                         @RequestParam(required=false) Long merchantId,
                         @RequestParam(required=false) Long categoryId,
                         @RequestParam(required=false) Integer shelfStatus) {
        Long operatorId = currentUserId();
        Set<Long> boundMerchants = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());

        LambdaQueryWrapper<GsProduct> qw = new LambdaQueryWrapper<GsProduct>();
        // 只展示已绑定销售商的商品
        if (!boundMerchants.isEmpty()) qw.in(GsProduct::getMerchantId, boundMerchants);
        else qw.eq(GsProduct::getMerchantId, -1L); // 无绑定则无商品
        if (merchantId != null) qw.eq(GsProduct::getMerchantId, merchantId);
        if (categoryId != null) qw.eq(GsProduct::getCategoryId, categoryId);
        if (shelfStatus != null) qw.eq(GsProduct::getShelfStatus, shelfStatus);
        qw.orderByDesc(GsProduct::getId);

        Page<GsProduct> page = productMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        List<Map<String,Object>> enriched = page.getRecords().stream().map(p -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("name", p.getName());
            m.put("coverImage", p.getCoverImage()); m.put("pointsPrice", p.getPointsPrice());
            m.put("cashPrice", p.getCashPrice()); m.put("shelfStatus", p.getShelfStatus());
            m.put("status", p.getStatus()); m.put("clickCount", p.getClickCount());
            m.put("merchantId", p.getMerchantId()); m.put("categoryId", p.getCategoryId());
            // 分类名称
            if (p.getCategoryId() != null) {
                ProductCategory c = categoryMapper.selectById(p.getCategoryId());
                m.put("categoryName", c != null ? c.getName() : "-");
            } else m.put("categoryName", "-");
            return m;
        }).collect(Collectors.toList());

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("records", enriched);
        result.put("total", page.getTotal());
        result.put("size", page.getSize());
        result.put("current", page.getCurrent());
        return R.ok(result);
    }

    /** 单个商品上架/下架 */
    @PutMapping("/products/{id}/shelf-status")
    public R<?> toggleShelf(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        GsProduct p = new GsProduct(); p.setId(id); p.setShelfStatus(shelfStatus);
        productMapper.updateById(p);
        return R.ok();
    }

    /** 批量上下架 */
    @PostMapping("/products/batch-shelf")
    public R<?> batchShelf(@RequestBody Map<String,Object> body) {
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        GsProduct p = new GsProduct(); p.setShelfStatus(shelfStatus);
        for (Integer id : ids) { p.setId(Long.valueOf(id)); productMapper.updateById(p); }
        return R.ok(Map.of("count", ids.size()));
    }

    /** 批量设置分类 */
    @PutMapping("/products/batch-category")
    public R<?> batchCategory(@RequestBody Map<String,Object> body) {
        Long categoryId = Long.valueOf(body.getOrDefault("categoryId", 0).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        GsProduct p = new GsProduct(); p.setCategoryId(categoryId);
        for (Integer id : ids) { p.setId(Long.valueOf(id)); productMapper.updateById(p); }
        return R.ok(Map.of("count", ids.size()));
    }

    // ═══ 商品分类 CRUD ═══

    @GetMapping("/product-categories")
    public R<?> categories() {
        List<ProductCategory> list = categoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>().orderByAsc(ProductCategory::getSortOrder));
        // 构建树形
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
    public R<?> createCategory(@RequestBody ProductCategory cat) {
        categoryMapper.insert(cat);
        return R.ok();
    }

    @PutMapping("/product-categories/{id}")
    public R<?> updateCategory(@PathVariable Long id, @RequestBody ProductCategory cat) {
        cat.setId(id); categoryMapper.updateById(cat);
        return R.ok();
    }

    @DeleteMapping("/product-categories/{id}")
    public R<?> deleteCategory(@PathVariable Long id) {
        categoryMapper.deleteById(id);
        return R.ok();
    }

    private Map<String,Object> catToMap(ProductCategory c) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("name", c.getName());
        m.put("parentId", c.getParentId()); m.put("sortOrder", c.getSortOrder());
        return m;
    }
}
