package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 员工发起业务订单作废退款申请。
 *
 * <p>金额默认订单应付合计，允许下调但不得超过；退款原因必填；
 * 补充附件复用业务附件上传接口（fileType=REFUND），可选 0~5 个。</p>
 */
@Data
public class BusinessRefundApplyRequest {

    /** 退款原因（必填，≤500 字）。 */
    private String reason;

    /** 退款金额；为空时取订单应付合计。 */
    private BigDecimal amount;

    /** 备注（选填，≤500 字）。 */
    private String remark;

    /** 补充附件 ID 列表（ops_business_attachment.id，fileType=REFUND）。 */
    private List<Long> attachmentIds;
}
