package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 运营商管理后台 — 绑定销售商、商品管理、分类管理。
 *
 * 销售商来源：底座平台 sys_corp 表中，在 sys_corp_role 中拥有 role_name='销售商' 的组织。
 * 角色名称通过 platform.merchant-role-name 配置，默认为"销售商"。
 */
@RestController
@RequestMapping("/api/ops/admin")
public class AdminController {

    private final OperatorMerchantMapper omMapper;
    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final MerchantMapper merchantMapper;
    private final String merchantRoleName;

    public AdminController(OperatorMerchantMapper omMapper,
                           GsProductMapper productMapper,
                           ProductCategoryMapper categoryMapper,
                           MerchantMapper merchantMapper,
                           @Value("${platform.merchant-role-name:销售商}") String merchantRoleName) {
        this.omMapper = omMapper;
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.merchantMapper = merchantMapper;
        this.merchantRoleName = merchantRoleName;
    }

    private Long currentUserId() { return SecurityUtils.getCurrentUserId(); }

    // ═══ 绑定销售商 ═══

    /**
     * 获取销售商列表（含已绑定状态）。
     * 销售商 = 底座平台中拥有"销售商"企业角色的组织（sys_corp × sys_corp_role）。
     */
    @GetMapping("/merchants")
    public R<?> merchantList(@RequestParam(defaultValue="1") int pageNum,
                             @RequestParam(defaultValue="20") int pageSize,
                             @RequestParam(required=false) String keyword) {
        Long operatorId = currentUserId();

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
        if (!boundMerchants.isEmpty()) qw.in(GsProduct::getMerchantId, boundMerchants);
        else qw.eq(GsProduct::getMerchantId, -1L);
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

    @PutMapping("/products/{id}/shelf-status")
    public R<?> toggleShelf(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        GsProduct p = new GsProduct();
        p.setId(id); p.setShelfStatus(Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString()));
        productMapper.updateById(p);
        return R.ok();
    }

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
