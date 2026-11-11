package com.aip.ops.dto;

import lombok.Data;

/** 退货审批请求：approved=true 通过 / false 驳回；remark 审批意见。 */
@Data
public class BusinessReturnApproveRequest {
    private Boolean approved;
    private String remark;
}
