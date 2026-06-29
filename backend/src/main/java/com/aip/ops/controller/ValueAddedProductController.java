package com.aip.ops.controller;

import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.service.ValueAddedProductService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 增值业务 - 产品管理 CRUD
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/value-added/product")
@RequiredArgsConstructor
public class ValueAddedProductController {

    private final ValueAddedProductService service;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String brand,
                     @RequestParam(required = false) Integer status) {
        return R.ok(service.page(pageNum, pageSize, category, brand, status));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<?> create(@RequestBody OpsValueAddedProduct product) {
        product.setCorpId(SecurityUtils.getCurrentCorpId());
        // 向后兼容：旧前端可能仍传 operatorId
        if (product.getOperatorId() == null) product.setOperatorId(product.getCorpId());
        service.create(product);
        return R.ok("创建成功");
    }

    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsValueAddedProduct product) {
        service.update(id, product);
        return R.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok("删除成功");
    }
}
