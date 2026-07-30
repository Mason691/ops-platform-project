package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 品类使用年限规则。
 * 按品类配置使用年限、间隔安检期和提前提醒天数，供燃气设备生命周期管理使用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_category_lifecycle_rule")
public class OpsCategoryLifecycleRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    /** 品类 (PRODUCT_CATEGORY 字典 itemKey) */
    @TableField("category")
    private String category;

    /** 使用年限（年） */
    @TableField("max_years")
    private Integer maxYears;

    /** 间隔安检期（月） */
    @TableField("inspection_interval")
    private Integer inspectionInterval;

    /** 提前提醒天数 */
    @TableField("remind_days")
    private Integer remindDays;

    /** 备注 */
    @TableField("remark")
    private String remark;
}
