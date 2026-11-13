package com.aip.ops.dto;

import lombok.Data;

/**
 * 派工单取消 / 退回请求。
 */
@Data
public class DispatchReasonRequest {

    private String reason;
}
