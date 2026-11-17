package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.ProductPriceDtos;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.ProductPriceService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 增值业务 - 产品售价。
 *
 * <p>运营商管理员可维护；城市合伙人只读查看所属运营商配置；
 * 超管需切换到具体运营商组织后维护。</p>
 */
@RestController
@RequestMapping("/api/ops/admin/product-price")
@RequiredArgsConstructor
public class ProductPriceController {

    private final ProductPriceService service;
    private final OrgRoleService orgRoleService;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String brand,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String model,
                     @RequestParam(required = false) Integer status) {
        return R.ok(service.page(pageNum, pageSize, brand, category, model, status));
    }

    /** SCM 商品搜索：新增售价时选择商品。 */
    @GetMapping("/scm-products")
    public R<?> scmProducts(@RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") long pageNum,
                            @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(service.scmProducts(keyword, pageNum, pageSize));
    }

    @OperationLog(module = "增值业务", action = "新增产品售价", targetTable = "ops_product_price")
    @PostMapping
    public R<?> create(@RequestBody ProductPriceDtos.ProductPriceRequest request) {
        assertOperator();
        service.create(request);
        return R.ok("保存成功");
    }

    @OperationLog(module = "增值业务", action = "修改产品售价", targetTable = "ops_product_price")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id,
                       @RequestBody ProductPriceDtos.ProductPriceRequest request) {
        assertOperator();
        service.update(id, request);
        return R.ok("更新成功");
    }

    @OperationLog(module = "增值业务", action = "删除产品售价", targetTable = "ops_product_price")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        assertOperator();
        service.delete(id);
        return R.ok("删除成功");
    }

    @OperationLog(module = "增值业务", action = "启停产品售价", targetTable = "ops_product_price")
    @PutMapping("/{id}/status")
    public R<?> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        assertOperator();
        service.updateStatus(id, status);
        return R.ok("状态已更新");
    }

    private void assertOperator() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw com.aip.sdk.exception.BusinessException.of("请切换到具体运营商组织后维护产品售价");
        }
        if (!orgRoleService.isOperatorOrSuperAdmin() || orgRoleService.isCityPartner(corpId)) {
            throw com.aip.sdk.exception.BusinessException.forbidden("仅运营商管理员可维护产品售价");
        }
    }
}
