package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

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

    @TableField("brand")
    private String brand;

    @TableField("category")
    private String category;

    @TableField("model")
    private String model;

    @TableField("sale_price")
    private BigDecimal salePrice;

    @TableField("sale_date")
    private String saleDate;

    @TableField("receipt_date")
    private String receiptDate;

    @TableField("receipt_no")
    private String receiptNo;

    @TableField("agent")
    private String agent;

    @TableField("retail_floor_price")
    private BigDecimal retailFloorPrice;

    @TableField("group_lowest_price")
    private BigDecimal groupLowestPrice;

    @TableField("commission_rate")
    private BigDecimal commissionRate;

    @TableField("commission_amount")
    private BigDecimal commissionAmount;

    @TableField("calc_status")
    private String calcStatus;

    /**
     * @deprecated 使用 {@link #corpId} 替代；保留字段仅用于向后兼容
     */
    @Deprecated
    @TableField("operator_id")
    private Long operatorId;
}
