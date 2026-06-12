package com.aip.ops.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品展示管理 Service — 处理 ops_product 映射与 mall_product 联表逻辑。
 */
public interface IGsProductService {

    /**
     * 管理员分页查询本运营商关联的商品列表。
     * @return { records, total, size, current }
     */
    Map<String, Object> adminProductPage(Long operatorId, int pageNum, int pageSize,
                                          Integer shelfStatus, Long categoryId, Long merchantId, String keyword);

    /** 单个商品详情（通过映射ID） */
    Map<String, Object> productDetailByMappingId(Long mappingId);

    /** 上下架 */
    void updateShelfStatus(Long mappingId, Integer shelfStatus);

    /** 批量上下架 */
    int batchUpdateShelfStatus(List<Long> ids, Integer shelfStatus, Long operatorId);

    /** 批量设置分类 */
    int batchUpdateCategory(List<Long> ids, Long categoryId, Long operatorId);

    /** 设置单个商品积分配额 */
    void updatePointsConfig(Long mappingId, Integer points, BigDecimal exchangeAmount, Long operatorId);

    /** 批量设置积分配额 */
    int batchUpdatePointsConfig(List<Long> ids, Integer points, BigDecimal exchangeAmount, Long operatorId);

    /**
     * 用户端分页查询已上架商品。
     * @return { records, total, size, current }
     */
    Map<String, Object> publishedProductPage(Long corpId, int pageNum, int pageSize,
                                              Long categoryId, String keyword);
}
