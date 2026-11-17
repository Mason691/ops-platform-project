package com.aip.ops.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 物资领用提交请求。
 *
 * <p>商品名称/品牌/规格/单位由服务端按 materialId 从供应链回填，前端不可信。</p>
 */
public record MaterialRequisitionRequest(
        String purpose,
        LocalDate expectedDate,
        String remark,
        List<Long> applicantUserIds,
        List<Entry> entries
) {
    public record Entry(Long materialId, Integer quantity, String remark) {}
}
