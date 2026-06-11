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
 * 1. 管理员看到的商品 = gs_product JOIN mall_product，按 operator_id 过滤，
 *    且仅显示已绑定销售商（biz_operator_merchant）的商品
 * 2. 用户端看到的商品 = gs_product JOIN mall_product，按 shelf_status=1 过滤
 * 3. 上下架/分类操作前校验商品是否属于当前运营商绑定的销售商
 */
@Service
@RequiredArgsConstructor
public class GsProductServiceImpl implements IGsProductService {

    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final OperatorMerchantMapper omMapper;
    private final MerchantMapper merchantMapper;

    // ═══ 管理员商品管理 ═══

    @Override
    public Map<String, Object> adminProductPage(Long operatorId, int pageNum, int pageSize,
                                                 Integer shelfStatus, Long categoryId, Long merchantId, String keyword) {
        // 1. 获取当前运营商绑定的所有销售商 ID
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        // 2. 未绑定任何销售商 → 直接返回空列表
        if (boundMerchantIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("records", Collections.emptyList());
            empty.put("total", 0L);
            empty.put("size", pageSize);
            empty.put("current", pageNum);
            return empty;
        }

        // 3. 若前端指定了 merchantId 筛选，校验其是否在绑定列表中
        List<Long> corpIds;
        if (merchantId != null) {
            if (!boundMerchantIds.contains(merchantId)) {
                // 请求的销售商不在绑定列表中 → 返回空
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("records", Collections.emptyList());
                empty.put("total", 0L);
                empty.put("size", pageSize);
                empty.put("current", pageNum);
                return empty;
            }
            corpIds = Collections.singletonList(merchantId);
        } else {
            corpIds = new ArrayList<>(boundMerchantIds);
        }
        int offset = (pageNum - 1) * pageSize;

        // 4. 按绑定销售商过滤商品
        List<Map<String, Object>> records = productMapper.selectProductList(
                operatorId, corpIds, offset, pageSize, shelfStatus, categoryId, keyword);
        long total = productMapper.countProductList(operatorId, corpIds, shelfStatus, categoryId, keyword);

        // 5. 批量加载销售商名称（id → name）
        Map<Long, String> merchantNameMap = loadMerchantNames(boundMerchantIds);

        // 6. 富化每条记录
        List<Map<String, Object>> enriched = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            m.put("productId", rec.get("productId"));
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
            // 所属销售商（从 mall_product.corp_id 解析）
            Object merchantIdObj = rec.get("merchantId");
            String merchantName = "-";
            if (merchantIdObj != null) {
                Long mid = Long.valueOf(merchantIdObj.toString());
                merchantName = merchantNameMap.getOrDefault(mid, "未知销售商");
            }
            m.put("merchantId", merchantIdObj);
            m.put("merchantName", merchantName);
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
        // 单个操作暂不做权限校验（由批量操作统一入口），保留灵活性
        GsProduct p = new GsProduct();
        p.setId(mappingId);
        p.setShelfStatus(shelfStatus);
        productMapper.updateById(p);
    }

    @Override
    @Transactional
    public int batchUpdateShelfStatus(List<Long> ids, Integer shelfStatus, Long operatorId) {
        assertProductsBelongToOperator(ids, operatorId);
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
        assertProductsBelongToOperator(ids, operatorId);
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
        // 用户端默认使用 corpId=3（金粮源公司1），若前端传入 corpId 则使用传入值
        long effectiveCorpId = (corpId != null) ? corpId : 3L;

        List<Map<String, Object>> records = productMapper.selectPublishedProducts(
                effectiveCorpId, offset, pageSize, categoryId, keyword);
        long total = productMapper.countPublishedProducts(effectiveCorpId, categoryId, keyword);

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

    // ═══ 私有辅助方法 ═══

    /**
     * 获取运营商绑定的所有激活销售商 ID 集合。
     */
    private Set<Long> getBoundMerchantIds(Long operatorId) {
        return omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
    }

    /**
     * 批量查询销售商组织名称，返回 id → name 映射。
     */
    private Map<Long, String> loadMerchantNames(Set<Long> merchantIds) {
        if (merchantIds.isEmpty()) return Collections.emptyMap();
        List<Map<String, Object>> rows = merchantMapper.selectCorpNamesByIds(merchantIds);
        Map<Long, String> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = Long.valueOf(row.get("id").toString());
            String name = (String) row.get("name");
            map.put(id, name != null ? name : "未知销售商");
        }
        return map;
    }

    /**
     * 校验待操作的商品是否属于当前运营商绑定的销售商。
     * 通过 gs_product JOIN mall_product，检查 mall_product.corp_id 是否在绑定列表中。
     *
     * @param mappingIds 要操作的商品映射 ID 列表
     * @param operatorId 当前运营商组织 ID
     * @throws IllegalArgumentException 如果有商品不属于绑定的销售商
     */
    private void assertProductsBelongToOperator(List<Long> mappingIds, Long operatorId) {
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        if (boundMerchantIds.isEmpty()) {
            throw new IllegalArgumentException("当前运营商未绑定任何销售商，无法操作商品");
        }

        List<Long> authorized = productMapper.selectIdsInMerchants(
                mappingIds, new ArrayList<>(boundMerchantIds));

        if (authorized.size() != mappingIds.size()) {
            Set<Long> authorizedSet = new HashSet<>(authorized);
            List<Long> unauthorized = mappingIds.stream()
                    .filter(id -> !authorizedSet.contains(id))
                    .collect(Collectors.toList());
            throw new IllegalArgumentException("无权操作以下商品（不属于绑定的销售商）: " + unauthorized);
        }
    }
}
