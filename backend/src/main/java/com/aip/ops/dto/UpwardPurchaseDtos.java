package com.aip.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 向上采购申请：部长勾选、财务生成待发起、H5 列表与发起标记。 */
public final class UpwardPurchaseDtos {

    private UpwardPurchaseDtos() {
    }

    /** 部长审核提交的单条商品勾选；rowIds 为同商品组全部明细行。 */
    public record SelectionItem(String productKey, List<Long> rowIds, boolean checked) {
    }

    /** 其他城市合伙人库存快照。 */
    public record OtherStock(Long corpId, String corpName, BigDecimal stock) {
    }

    /** H5 列表记录（按订单+商品分组）。 */
    public record RecordView(
            Long id,
            Long orderId,
            String orderNo,
            String receiptNo,
            String batchNo,
            String customerName,
            String category,
            String brand,
            String model,
            BigDecimal quantity,
            BigDecimal salePrice,
            String unit,
            BigDecimal localStock,
            List<OtherStock> otherStocks,
            String productKey,
            List<Long> itemIds,
            String status,
            String statusName,
            String requestBillNo,
            String invalidReason,
            String selectionNode,
            LocalDateTime financeCheckTime
    ) {
    }

    public record PageResult(List<RecordView> records, long total, long pendingCount,
                             long current, long size) {
    }

    public record MarkRequestedRequest(Long orderId, String billNo) {
    }

    /** 发起采购申请前取该订单全部待发起明细行，避免分页把同一订单拆到多页导致漏项。 */
    public record PendingItems(List<Long> itemIds, List<Long> recordIds) {
    }

    public record PendingCount(long count) {
    }
}
