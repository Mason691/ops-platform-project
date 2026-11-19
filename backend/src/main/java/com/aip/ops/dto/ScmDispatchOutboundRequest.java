package com.aip.ops.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * ops → SCM 内部接口：按派工单生成销售出库单（未审核）。
 */
public record ScmDispatchOutboundRequest(
        Long corpId,
        Long dispatchId,
        String dispatchNo,
        Long sourceOrderId,
        String sourceOrderNo,
        Long operatorUserId,
        String operatorName,
        String customerName,
        String customerPhone,
        Long platformMemberId,
        String communityName,
        String detailedAddress,
        List<Item> items,
        /** 派工物料领用仓库（可选）：物料清单非空时，SCM 草稿直接带入该仓库 */
        String stockNumber,
        /** 派工物料清单（可选）：SCM 在同一事务内生成物料领用草稿；空则只出库不生成领用单 */
        List<MaterialItem> materialList
) {
    public record Item(
            Long sourceItemId,
            String brand,
            String category,
            String model,
            Integer quantity,
            BigDecimal salePrice
    ) {
    }

    public record MaterialItem(String materialNumber, BigDecimal qty) {
    }
}
