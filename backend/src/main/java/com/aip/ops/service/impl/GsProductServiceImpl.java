package com.aip.ops.service.impl;

import com.aip.ops.entity.GsProduct;
import com.aip.ops.entity.OperatorMerchant;
import com.aip.ops.entity.ProductCategory;
import com.aip.ops.mapper.*;
import com.aip.ops.service.IGsProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品展示管理 Service 实现。
 *
 * 核心逻辑：
 * 1. 管理员看到的商品 = gs_product JOIN mall_product，按 operator_id 过滤
 * 2. 用户端看到的商品 = gs_product JOIN mall_product，按 shelf_status=1 过滤
 * 3. 上下架/分类操作直接修改 gs_product 记录
 */
@Service
@RequiredArgsConstructor
public class GsProductServiceImpl implements IGsProductService {

    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final OperatorMerchantMapper omMapper;

    private static final Long CORP_ID = 3L;              // 金粮源公司1 的 corp_id
    private static final String MERCHANT_NAME = "金粮源公司1";

    // ═══ 管理员商品管理 ═══

    @Override
    public Map<String, Object> adminProductPage(Long operatorId, int pageNum, int pageSize,
                                                 Integer shelfStatus, Long categoryId, String keyword) {
        int offset = (pageNum - 1) * pageSize;

        List<Map<String, Object>> records = productMapper.selectProductList(
                operatorId, CORP_ID, offset, pageSize, shelfStatus, categoryId, keyword);
        long total = productMapper.countProductList(operatorId, CORP_ID, shelfStatus, categoryId, keyword);

        // 加载已绑定销售商列表，用于展示所属销售商名称
        Set<Long> boundMerchantIds = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());

        // 富化每条记录
        List<Map<String, Object>> enriched = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));                    // gs_product.id
            m.put("productId", rec.get("productId"));             // mall_product.id
            m.put("name", rec.get("name"));
            m.put("coverImage", rec.get("coverImage"));
            m.put("images", rec.get("images"));
            m.put("videoUrl", rec.get("videoUrl"));
            m.put("description", rec.get("description"));
            m.put("pointsPrice", rec.get("pointsPrice"));
            m.put("cashPrice", rec.get("cashPrice"));
            m.put("maxPointsDeduct", rec.get("maxPointsDeduct"));
            m.put("clickCount", rec.get("clickCount") != null ? rec.get("clickCount") : 0);
            m.put("status", rec.get("status"));
            m.put("shelfStatus", rec.get("shelfStatus"));
            m.put("categoryId", rec.get("categoryId"));
            // 分类名称
            Object cid = rec.get("categoryId");
            if (cid != null) {
                ProductCategory cat = categoryMapper.selectById(Long.valueOf(cid.toString()));
                m.put("categoryName", cat != null ? cat.getName() : "-");
            } else {
                m.put("categoryName", "-");
            }
            // 所属销售商
            m.put("merchantName", MERCHANT_NAME);
            m.put("createTime", rec.get("createTime"));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", enriched);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    @Override
    public Map<String, Object> productDetailByMappingId(Long mappingId) {
        return productMapper.selectProductByMappingId(mappingId);
    }

    @Override
    public void updateShelfStatus(Long mappingId, Integer shelfStatus) {
        GsProduct p = new GsProduct();
        p.setId(mappingId);
        p.setShelfStatus(shelfStatus);
        productMapper.updateById(p);
    }

    @Override
    @Transactional
    public int batchUpdateShelfStatus(List<Long> ids, Integer shelfStatus, Long operatorId) {
        GsProduct p = new GsProduct();
        p.setShelfStatus(shelfStatus);
        int count = 0;
        for (Long id : ids) {
            p.setId(id);
            productMapper.updateById(p);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public int batchUpdateCategory(List<Long> ids, Long categoryId, Long operatorId) {
        GsProduct p = new GsProduct();
        p.setCategoryId(categoryId);
        int count = 0;
        for (Long id : ids) {
            p.setId(id);
            productMapper.updateById(p);
            count++;
        }
        return count;
    }

    // ═══ 用户端商品浏览 ═══

    @Override
    public Map<String, Object> publishedProductPage(Long corpId, int pageNum, int pageSize,
                                                     Long categoryId, String keyword) {
        int offset = (pageNum - 1) * pageSize;
        long effectiveCorpId = (corpId != null) ? corpId : CORP_ID;

        List<Map<String, Object>> records = productMapper.selectPublishedProducts(
                effectiveCorpId, offset, pageSize, categoryId, keyword);
        long total = productMapper.countPublishedProducts(effectiveCorpId, categoryId, keyword);

        // 将字段映射为前端期望的驼峰格式
        List<Map<String, Object>> mapped = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            m.put("productId", rec.get("productId"));
            m.put("name", rec.get("name"));
            m.put("coverImage", rec.get("coverImage"));
            m.put("pointsPrice", rec.get("pointsPrice"));
            m.put("cashPrice", rec.get("cashPrice"));
            m.put("maxPointsDeduct", rec.get("maxPointsDeduct"));
            m.put("description", rec.get("description"));
            m.put("clickCount", rec.get("clickCount") != null ? rec.get("clickCount") : 0);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", mapped);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }
}
