package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 年度任务计划 — 各子公司各品类的年度计划金额明细。
 *
 * <p>汇总数据实时计算，不在表中存储。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_annual_plan")
public class OpsAnnualPlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 年度 */
    @TableField("year")
    private Integer year;

    /** 组织ID（子公司） */
    @TableField("org_id")
    private Long orgId;

    /** 品类代码（对应数据字典 item_key） */
    @TableField("category")
    private String category;

    /** 计划金额 */
    @TableField("plan_amount")
    private BigDecimal planAmount;

    /** 审核状态 0=待审核 1=已审核 */
    @TableField("status")
    private Integer status;

    /** 运营商ID（操作组织） */
    @TableField("operator_id")
    private Long operatorId;

    // remark / createTime / modifyTime / deleteFlag 等审计字段由 BaseEntity 提供
}
