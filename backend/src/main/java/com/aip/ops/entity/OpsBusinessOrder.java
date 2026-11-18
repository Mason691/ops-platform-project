package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 增值业务订单头（主表）。
 *
 * <p>一条业务订单一行，承载订单级公共信息与合计；商品×经办人明细存
 * {@link OpsBusinessData}（表 ops_business_order_item）。拆分后保留
 * ops_business_data 作为子表的兼容视图，供 SCM / AI 只读。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_order")
public class OpsBusinessOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 订单幂等键：新单为 O|{orderNo}；历史迁移数据按订单编号/业务组号/收据号归组。 */
    @TableField("order_key")
    private String orderKey;

    @TableField("batch_no")
    private String batchNo;

    @TableField("order_no")
    private String orderNo;

    @TableField("business_group_no")
    private String businessGroupNo;

    @TableField("receipt_no")
    private String receiptNo;

    /** 收据图片 URL（用于 OCR 识别收据号；可空）。 */
    @TableField("receipt_image_url")
    private String receiptImageUrl;

    /** 支付凭证 URL 列表（逗号分隔，最多 3 张；与 legacy voucher_url 双写）。 */
    @TableField("payment_voucher_urls")
    private String paymentVoucherUrls;

    /** 合同附件 URL 列表（逗号分隔，最多 5 份，图片/PDF）。 */
    @TableField("contract_attachment_urls")
    private String contractAttachmentUrls;

    @TableField("company")
    private String company;

    @TableField("customer")
    private String customer;

    @TableField("customer_phone")
    private String customerPhone;

    @TableField("platform_member_id")
    private Long platformMemberId;

    @TableField("community_id")
    private Long communityId;

    @TableField("community_name")
    private String communityName;

    @TableField("detailed_address")
    private String detailedAddress;

    @TableField("agent_names")
    private String agentNames;

    @TableField("agent_phones")
    private String agentPhones;

    @TableField("receipt_date")
    private String receiptDate;

    @TableField("finance_receipt_date")
    private LocalDate financeReceiptDate;

    @TableField("fund_dynamic_rows")
    private Integer fundDynamicRows;

    @TableField("is_received")
    private String isReceived;

    /** 支付方式：QR=二维码 / CASH=现金 / TRANSFER=转账（新单使用；is_received 保留历史）。 */
    @TableField("payment_method")
    private String paymentMethod;

    @TableField("voucher_url")
    private String voucherUrl;

    /** 订单合计金额：订单内 品类+品牌+型号+单价 去重后求和。 */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 订单提成合计：子表 commission_amount 之和（多经办人已均分，合计不变）。 */
    @TableField("total_commission")
    private BigDecimal totalCommission;

    /** 是否含以旧换新（0否 1是），用于施工完成提醒与列表标记。 */
    @TableField("has_trade_in")
    private Integer hasTradeIn;

    /** 以旧换新抵扣合计（按产品组 MAX 去重）。 */
    @TableField("trade_in_deduction_amount")
    private BigDecimal tradeInDeductionAmount;

    /** 应付合计 = total_amount - trade_in_deduction_amount。 */
    @TableField("payable_amount")
    private BigDecimal payableAmount;

    /** 以旧换新自动采购单状态：PENDING/SUCCESS/FAILED。 */
    @TableField("trade_in_purchase_status")
    private String tradeInPurchaseStatus;

    /** 关联 SCM 采购订单ID（scm_pur_order.id）。 */
    @TableField("trade_in_purchase_order_id")
    private Long tradeInPurchaseOrderId;

    /** 关联 SCM 采购订单号。 */
    @TableField("trade_in_purchase_bill_no")
    private String tradeInPurchaseBillNo;

    /** 采购单创建失败原因（FAILED 时展示）。 */
    @TableField("trade_in_purchase_error")
    private String tradeInPurchaseError;

    @TableField("calc_status")
    private String calcStatus;
    /**
     * 付款方式（制单时选择，制单后不变）：FULL=全款支付 / DEPOSIT=定金支付。
     * 注意：与 payment_method（二维码/现金/转账）是两个维度。
     */
    @TableField("payment_type")
    private String paymentType;

    /** 是否已收齐全款：1=是 / 0=否。定金支付初始 0，货款补录后置 1；派工出库看此字段。 */
    @TableField("is_full_payment")
    private Integer isFullPayment;

    @TableField("deposit_amount")
    private BigDecimal depositAmount;

    /** 应补余款 = 销售价合计 - 定金；服务端计算，不接受前端传值。 */
    @TableField("balance_amount")
    private BigDecimal balanceAmount;

    /** 实收余款（货款补录时填写，默认必须等于应补余款）。 */
    @TableField("balance_received_amount")
    private BigDecimal balanceReceivedAmount;

    @TableField("balance_paid_at")
    private LocalDateTime balancePaidAt;

    @TableField("balance_voucher_url")
    private String balanceVoucherUrl;

    @TableField("balance_remark")
    private String balanceRemark;

    // 订单备注使用 BaseEntity.remark（ops_business_order.remark 列已存在），不重复声明

    /** 业务状态（工作流驱动，字典 business_order_status：0~6）。 */
    @TableField("biz_status")
    private String bizStatus;

    /** 当前工作流实例 ID（wf_instance.id，平台工作流组件）。 */
    @TableField("wf_instance_id")
    private Long wfInstanceId;

    @TableField("wf_node_order")
    private Integer wfNodeOrder;

    @TableField("wf_node_name")
    private String wfNodeName;

    @TableField("finance_check_user_id")
    private Long financeCheckUserId;

    @TableField("finance_check_user_name")
    private String financeCheckUserName;

    @TableField("finance_check_time")
    private LocalDateTime financeCheckTime;

    /** 财务核对结论：PASS / REJECT / HOLD。 */
    @TableField("finance_check_conclusion")
    private String financeCheckConclusion;

    @TableField("finance_check_remark")
    private String financeCheckRemark;
}
