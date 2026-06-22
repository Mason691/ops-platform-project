package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 活动-商品关联表 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_activity_product")
public class OpsActivityProduct extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableField("corp_id")
    private Long corpId;

    @TableField("activity_id")
    private Long activityId;

    @TableField("product_id")
    private Long productId;

    @TableField("sort")
    private Integer sort;
}
