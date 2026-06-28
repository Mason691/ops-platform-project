package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/**
 * 个人提成规则 — category 存 dict item_key, 类型分阶梯/固定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_personal_commission_rule")
public class OpsPersonalCommissionRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 品类 (sys_dict_item.item_key, type_id=9) */
    @TableField("category")
    private String category;

    /** 提成类型: 阶梯 / 固定 */
    @TableField("commission_type")
    private String commissionType;

    /** 提成比例(%) */
    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /** 阶梯规则描述 */
    @TableField("rule_desc")
    private String ruleDesc;

    /** 父项ID (子项分组) */
    @TableField("parent_id")
    private Long parentId;

    /** 优先级 */
    @TableField("priority")
    private Integer priority;

    /** 状态: 1启用 0禁用 */
    @TableField("status")
    private Integer status;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 运营商ID */
    @TableField("operator_id")
    private Long operatorId;
}
