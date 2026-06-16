package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 活动-商品关联表 */
@Data
@TableName("ops_activity_product")
public class OpsActivityProduct {
    @TableField("activity_id")
    private Long activityId;

    @TableField("product_id")
    private Long productId;

    @TableField("sort")
    private Integer sort;

    @TableField("create_time")
    private LocalDateTime createTime;
}
