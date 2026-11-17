package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 货款补录请求：实收余款 + 收款凭证 + 备注。 */
@Data
public class BalanceReceiveRequest {

    /** 实收余款（>0，默认必须等于应补余款，最多两位小数） */
    private BigDecimal receivedAmount;

    /** 收款凭证 URL（必填，复用现有上传能力） */
    private String voucherUrl;

    /** 收款备注（选填，≤500 字） */
    private String remark;
}
