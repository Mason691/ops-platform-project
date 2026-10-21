package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 业务数据 — 从 Excel 导入的销售记录，支持提成计算与导出。
 *
 * <p>删除策略：<b>物理删除</b>（不走 @TableLogic）。
 * 业务数据为导入的统计明细，纠错时需直接删除重导，逻辑删除无实际意义。
 * 参见 Specs/增值业务-规格说明.md。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_data")
public class OpsBusinessData extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    @TableField("batch_no")
    private String batchNo;

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

    @TableField("brand")
    private String brand;

    @TableField("category")
    private String category;

    @TableField("model")
    private String model;

    @TableField("sale_quantity")
    private Integer saleQuantity;

    @TableField("sale_price")
    private BigDecimal salePrice;

    @TableField("receipt_amount")
    private BigDecimal receiptAmount;

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

    @TableField("commission_amount")
    private BigDecimal commissionAmount;

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
