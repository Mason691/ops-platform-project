package com.aip.ops.dto;

import lombok.Data;

/** 退货申请请求：数量 + 原因。 */
@Data
public class BusinessReturnRequest {
    private Integer returnQuantity;
    private String reason;
}
