package com.aip.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * SCM 销售退货审核/反审核 → ops 业务数据负数分录反写请求。
 *
 * <p>审核时按 {@code qty} 向原订单新增负数分录（数量/金额/提成为负），
 * 以 ops 侧台账 `ops_scm_return_sync` 做幂等；反审核删除本单负数分录。
 * {@code cumulativeReturnedQty} 为 SCM 端累计量，仅保留用于滚动发布兼容与排查。</p>
 */
@Data
public class ScmSaleReturnSyncRequest {

    private Long corpId;
    private Long scmReturnId;
    private String billNo;
    /** AUDITED / DRAFT */
    private String auditStatus;
    private List<Entry> entries;

    @Data
    public static class Entry {
        /** 业务数据行ID（优先；多经办人时任一代表行即可，服务端按产品组同步） */
        private List<Long> rowIds;
        /** 无 rowIds 时按收据号回退匹配 */
        private List<String> receiptNos;
        /** 本次退货数量（生成负数分录的绝对数量，必须为正整数） */
        private Integer qty;
        /** SCM 端累计退货数量（滚动发布兼容/排查用，服务端不依赖） */
        private Integer cumulativeReturnedQty;
    }
}
