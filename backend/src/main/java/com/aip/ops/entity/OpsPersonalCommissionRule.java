package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 个人提成规则 — 按品类匹配，支持 阶梯 / 固定 / 固定金额 三种提成类型。
 *
 * <p>status 字段取值映射系统字典 ENABLE_DISABLE：1=启用 0=禁用</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_personal_commission_rule")
public class OpsPersonalCommissionRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    /** 品类 (sys_dict_item.item_key, type=PRODUCT_CATEGORY) */
    @TableField("category")
    private String category;

    /** 提成类型: 阶梯 / 固定 */
    @TableField("commission_type")
    private String commissionType;

    /** 提成比例(%) */
    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /** 提成金额（当提成类型为“固定金额”时使用，直接作为提成，不按销售额计算） */
    @TableField("commission_amount")
    private BigDecimal commissionAmount;

    /** 阶梯规则描述 */
    @TableField("rule_desc")
    private String ruleDesc;

    /** 父项ID（子项分组） */
    @TableField("parent_id")
    private Long parentId;

    /** 优先级 */
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
