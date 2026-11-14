package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 财务核对填报请求。
 * 只允许修改收据号、财务收款日期、资金动态表横排数、是否收款四项；
 * 核对人/核对时间由服务端从 JWT + 平台用户信息派生，不接受前端传入。
 */
@Data
public class FinanceCheckRequest {

    /** 收据号（必填，同 corp 内唯一，与导入口径一致） */
    private String receiptNo;

    /** 财务收款日期 yyyy-MM-dd（必填，不得早于市场收款日期） */
    private String financeReceiptDate;

    /** 资金动态表横排数（可空；非空时服务端严格校验为 >=0 的整数） */
    private BigDecimal fundDynamicRows;

    /** 是否收款（是/否，必填） */
    private String isReceived;

    /** 核对结论：PASS=通过 / REJECT=不通过 / HOLD=暂缓（工作流节点表单必填）。 */
    private String checkConclusion;

    /** 核对备注 / 不通过原因。 */
    private String checkRemark;
}
