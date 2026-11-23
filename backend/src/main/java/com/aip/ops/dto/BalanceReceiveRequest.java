package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 货款补录请求：实收余款 + 收款方式/日期/凭证/收据号 + 备注。 */
@Data
public class BalanceReceiveRequest {

    /** 实收余款（>0，默认必须等于应补余款，最多两位小数；当前不支持部分补录） */
    private BigDecimal receivedAmount;

    /** 尾款收款方式：QR/CASH/TRANSFER（中文亦可，服务端统一归一化） */
    private String paymentMethod;

    /** 尾款收款日期（yyyy-MM-dd，必填，不得晚于今天） */
    private String receiptDate;

    /** 尾款收据号（选填，≤50 字符；填写时校验字符集） */
    private String receiptNo;

    /** 收款凭证 URL（必填，复用现有上传能力） */
    private String voucherUrl;

    /** 收款备注（必填，≤500 字） */
    private String remark;
}
