package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 公司提成规则 — 按品类/品牌/型号匹配，支持按比例或固定金额计算提成。
 *
 * <p>status 字段取值映射系统字典 ENABLE_DISABLE：1=启用 0=禁用</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_company_commission_rule")
public class OpsCompanyCommissionRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    /** 品类：燃气灶/抽油烟机/壁挂炉/热水器 */
    @TableField("category")
    private String category;

    /** 品牌（为空表示所有品牌） */
    @TableField("brand")
    private String brand;

    /** 型号（为空表示所有型号） */
    @TableField("model")
    private String model;

    /** 最低销售额 */
    @TableField("min_amount")
    private BigDecimal minAmount;

    /** 最高销售额（0=不设上限） */
    @TableField("max_amount")
    private BigDecimal maxAmount;

    /** 提成比例(%) */
    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /** 固定提成金额 */
    @TableField("commission_fixed")
    private BigDecimal commissionFixed;

    /** 规则类型：percentage/fixed */
    @TableField("rule_type")
    private String ruleType;

    /** 优先级（数字越大越优先） */
    @TableField("priority")
    private Integer priority;

    /** 状态：1=启用 0=禁用（ENABLE_DISABLE 字典） */
    @TableField("status")
    private Integer status;

    /**
     * @deprecated 使用 {@link #corpId} 替代；保留字段仅用于向后兼容
     */
    @Deprecated
    @TableField("operator_id")
    private Long operatorId;
}
