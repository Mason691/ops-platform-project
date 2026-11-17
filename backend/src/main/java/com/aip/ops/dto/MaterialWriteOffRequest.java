package com.aip.ops.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 物资核销提交请求。
 *
 * <p>允许部分核销：每条明细 writeOffQty + returnQty ≤ 可核销余量；
 * 至少一条明细 writeOffQty/returnQty 大于 0。</p>
 */
public record MaterialWriteOffRequest(
        Long requisitionId,
        LocalDate writeOffDate,
        String usageDesc,
        String remark,
        List<String> voucherUrls,
        List<Long> writeOffUserIds,
        List<Entry> entries
) {
    public record Entry(Long requisitionEntryId, Integer writeOffQty, Integer returnQty,
                        String remark) {}
}
