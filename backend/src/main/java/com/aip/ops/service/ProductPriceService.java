package com.aip.ops.service;

import com.aip.ops.client.ScmBaseDataClient;
import com.aip.ops.dto.ProductPriceDtos;

import java.util.Map;

/**
 * 增值业务产品售价：CRUD + 员工制单按商品查价。
 */
public interface ProductPriceService {

    Map<String, Object> page(int pageNum, int pageSize, String brand, String category,
                             String model, Integer status);

    /** SCM 商品分页搜索（产品售价新增时选择商品）。 */
    ScmBaseDataClient.ScmMaterialPage scmProducts(String keyword, long pageNum, long pageSize);

    void create(ProductPriceDtos.ProductPriceRequest request);

    void update(Long id, ProductPriceDtos.ProductPriceRequest request);

    void delete(Long id);

    void updateStatus(Long id, Integer status);

    /**
     * 员工制单查价：按员工组织解析所属运营商，再按商品ID（优先）或品牌/品类/型号查询启用中的售价。
     */
    ProductPriceDtos.ProductPriceInfo lookupForEmployee(Long employeeCorpId, Long productId,
                                                        String brand, String category, String model);
}
