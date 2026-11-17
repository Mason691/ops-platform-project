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
        List<Item> items
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
}
