package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.ValueAddedProductService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValueAddedProductServiceImpl implements ValueAddedProductService {

    private final OpsValueAddedProductMapper mapper;
    private final PlatformInternalClient platformInternalClient;

    private volatile Set<String> categoryCache;
    private static final Set<String> DEFAULT_CATEGORIES =
            Set.of("燃气灶", "抽油烟机", "壁挂炉", "热水器");

    private Set<String> getValidCategories() {
        if (categoryCache != null) return categoryCache;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
            Object data = resp.get("data");
            if (data instanceof List) {
                Set<String> set = new HashSet<>();
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        Object val = ((Map<?, ?>) item).get("itemKey");
                        if (val != null) set.add(val.toString());
                    }
                }
                if (!set.isEmpty()) { categoryCache = set; return set; }
            }
        } catch (Exception e) { log.warn("加载 PRODUCT_CATEGORY 字典失败", e); }
        return DEFAULT_CATEGORIES;
    }

    private void validateCategory(String category) {
        if (category != null && !getValidCategories().contains(category)) {
            throw new BusinessException("品类必须为：" + String.join("/", getValidCategories()));
        }
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status) {
        LambdaQueryWrapper<OpsValueAddedProduct> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) qw.eq(OpsValueAddedProduct::getCategory, category);
        if (brand != null && !brand.isEmpty()) qw.eq(OpsValueAddedProduct::getBrand, brand);
        if (model != null && !model.isEmpty()) qw.like(OpsValueAddedProduct::getModel, model.trim());
        if (status != null) qw.eq(OpsValueAddedProduct::getStatus, status);
        qw.orderByAsc(OpsValueAddedProduct::getCategory).orderByAsc(OpsValueAddedProduct::getId);

        // Manual count (avoid IPage.getTotal() returning 0 for non-BaseEntity)
        long total = mapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsValueAddedProduct> records = mapper.selectList(qw);
        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum);
    }

    @Override
    public OpsValueAddedProduct getById(Long id) {
        OpsValueAddedProduct p = mapper.selectById(id);
        if (p == null) throw BusinessException.notFound("产品不存在");
        return p;
    }

    @Override
    @Transactional
    public void create(OpsValueAddedProduct product) {
        validateCategory(product.getCategory());
        if (product.getStatus() == null) product.setStatus(1);
        mapper.insert(product);
    }

    @Override
    @Transactional
    public void update(Long id, OpsValueAddedProduct product) {
        getById(id);
        validateCategory(product.getCategory());
        product.setId(id);
        mapper.updateById(product);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }
}
