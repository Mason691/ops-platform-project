package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 业务数据（商品分录，子表 ops_business_order_item）— 商品 × 经办人粒度明细。
 *
 * <p>2026-11 主子表拆分后本表为订单子表，订单级公共信息以
 * {@link OpsBusinessOrder} 为准；旧名 ops_business_data 保留为兼容视图，
 * 供 SCM / AI 只读。</p>
 *
 * <p>删除策略：<b>物理删除</b>（不走 @TableLogic）。
 * 业务数据为导入的统计明细，纠错时需直接删除重导，逻辑删除无实际意义。
 * 参见 Specs/增值业务-规格说明.md、Specs/业务数据主子表拆分-规格说明.md。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_order_item")
public class OpsBusinessData extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单头ID（ops_business_order.id） */
    @TableField("order_id")
    private Long orderId;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    @TableField("batch_no")
    private String batchNo;

    /**
     * 订单编号（dd + 全局流水号）：同一次提交的所有「产品×经办人」明细共享。
     * 由后端提交时生成，前端不可传入；用于按订单聚合展示/财务核对。
     */
    @TableField("order_no")
    private String orderNo;

    /**
     * 员工手工业务组号：一笔手工业务的所有「产品×经办人」行共享同一组号。
     * 提成均分与统计去重优先按订单编号，其次按组号；历史导入行为 NULL。
     */
    @TableField("business_group_no")
    private String businessGroupNo;

    @TableField("company")
    private String company;

    @TableField("customer")
    private String customer;

    /**
     * 客户手机号（新模板必填列）。与 sys_member.phone 一致（uk_phone 全局唯一），
     * 是平台会员归属的唯一依据；存量行无来源为 NULL。
     */
    @TableField("customer_phone")
    private String customerPhone;

    /**
     * 平台会员ID（= ai_platform.sys_member.id）——销售设备归属会员。
     * 归属规则：新导入按 customer_phone ↔ sys_member.phone 匹配（无歧义，必填）；
     * 存量行由 sql/migrate_business_data_platform_member.sql 按姓名一次性回填。
     */
    @TableField("platform_member_id")
    private Long platformMemberId;

    /**
     * 小区ID（= ai_platform.sys_community.id；选填）。
     * 员工手工录入按组织校验后写入；Excel 导入按小区名称匹配成功时回填。
     */
    @TableField("community_id")
    private Long communityId;

    /** 小区名称（冗余展示；导入/手工录入均写入，便于列表与出库打印直接展示）。 */
    @TableField("community_name")
    private String communityName;

    /**
     * 详细地址（楼栋/单元/门牌号；手工录入必填，导入选填）。
     * 历史「收货地址」列整列迁移至此，原值可能含小区名称。
     */
    @TableField("detailed_address")
    private String detailedAddress;

    @TableField("brand")
    private String brand;

    @TableField("category")
    private String category;

    @TableField("model")
    private String model;

    /** 销售数量：正常分录恒为原始数量，退货由独立负数分录表达（&lt; 0） */
    @TableField("sale_quantity")
    private Integer saleQuantity;

    @TableField("sale_price")
    private BigDecimal salePrice;

    /** 制单时产品售价表的标准销售价（审计留存；手工单写入） */
    @TableField("standard_sale_price")
    private BigDecimal standardSalePrice;

    @TableField("receipt_amount")
    private BigDecimal receiptAmount;

    /** 是否以旧换新（是/否）；提成仍按 receipt_amount 计算，抵扣不冲减提成基数。 */
    @TableField("is_trade_in")
    private String isTradeIn;

    /** 以旧换新抵扣金额（同一产品多经办人行写相同值，订单汇总按产品组 MAX 去重）。 */
    @TableField("deduction_amount")
    private BigDecimal deductionAmount;

    /** 抵扣后金额 = receipt_amount - deduction_amount（展示/应付口径）。 */
    @TableField("trade_in_payable_amount")
    private BigDecimal tradeInPayableAmount;

    @TableField("sale_date")
    private String saleDate;

    /** 市场收款日期（原"收款日期"，展示名变更，字段名保留） */
    @TableField("receipt_date")
    private String receiptDate;

    /** 财务收款日期 */
    @TableField("finance_receipt_date")
    private LocalDate financeReceiptDate;

    /** 资金动态表横排数 */
    @TableField("fund_dynamic_rows")
    private Integer fundDynamicRows;

    @TableField("receipt_no")
    private String receiptNo;

    /** 是否收款（是/否）——历史字段，新单不再使用（改由 payment_method） */
    @TableField("is_received")
    private String isReceived;

    /** 支付方式：QR=二维码 / CASH=现金 / TRANSFER=转账 */
    @TableField("payment_method")
    private String paymentMethod;

    /** 财务核对人ID（ai_platform.sys_user.id） */
    @TableField("finance_check_user_id")
    private Long financeCheckUserId;

    /** 财务核对人姓名（冗余展示/追溯） */
    @TableField("finance_check_user_name")
    private String financeCheckUserName;

    /** 财务核对时间（非空=已核对） */
    @TableField("finance_check_time")
    private LocalDateTime financeCheckTime;

    /** 收款凭证 URL（最多 3 张，英文逗号分隔）——员工手工录入时上传 */
    @TableField("voucher_url")
    private String voucherUrl;

    /** 部门 */
    @TableField("department")
    private String department;

    @TableField("agent")
    private String agent;

    @TableField("agent_phone")
    private String agentPhone;

    @TableField("retail_floor_price")
    private BigDecimal retailFloorPrice;

    @TableField("group_lowest_price")
    private BigDecimal groupLowestPrice;

    /** 是否特批（是/否）——"是"时提成按提成比例字段直接计算，跳过个人提成规则匹配 */
    @TableField("is_special")
    private String isSpecial;

    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /** 低价特批文件ID（ops_business_special_file.id） */
    @TableField("special_file_id")
    private Long specialFileId;

    /** 特批文件访问地址 */
    @TableField("special_file_url")
    private String specialFileUrl;

    /** 特批文件原始文件名 */
    @TableField("special_file_name")
    private String specialFileName;

    @TableField("commission_amount")
    private BigDecimal commissionAmount;

    /**
     * SCM 销售退货单ID（= scm_sale_return.id）。空=正常销售分录；
     * 非空=退货负数分录（sale_quantity &lt; 0），同一退货单同一产品组的多个经办人行共享。
     */
    @TableField("source_return_id")
    private Long sourceReturnId;

    /** SCM 退货单号（冗余展示/跳转）；历史迁移生成的冲减分录为“历史退货” */
    @TableField("source_return_no")
    private String sourceReturnNo;

    @TableField("calc_status")
    private String calcStatus;

    /** 备注（Excel 备注列直接映射） */
    @TableField("remark")
    private String remark;

    @TableField("usage_date")
    private LocalDate usageDate;

    /**
     * @deprecated 使用 {@link #corpId} 替代；保留字段仅用于向后兼容
     */
    @Deprecated
    @TableField("operator_id")
    private Long operatorId;
}
