package com.aip.ops.dto;

import java.math.BigDecimal;

/**
 * 产品售价 DTO。
 */
public final class ProductPriceDtos {

    private ProductPriceDtos() {
    }

    /** 新增/编辑请求。品牌、型号以 SCM 商品信息为准，不由前端传入。 */
    public record ProductPriceRequest(
            Long productId,
            String category,
            BigDecimal salePrice,
            BigDecimal retailFloorPrice,
            BigDecimal groupLowestPrice,
            Integer status,
            String remark
    ) {
    }

    /** 员工制单查询结果：configured=false 时前端阻止制单并展示 message。 */
    public record ProductPriceInfo(
            boolean configured,
            String message,
            Long productId,
            BigDecimal salePrice,
            BigDecimal retailFloorPrice,
            BigDecimal groupLowestPrice,
            String category
    ) {
    }
}
