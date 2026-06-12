package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("ops_coupon")
public class GsCoupon {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("name") private String name;
    @TableField("type") private String type;
    @TableField("value") private Integer value;
    @TableField("min_points") private Integer minPoints;
    @TableField("total_qty") private Integer totalQty;
    @TableField("remain_qty") private Integer remainQty;
    @TableField("expire_days") private Integer expireDays;
    @TableField("status") private Integer status;
    @TableField("create_time") private LocalDateTime createTime;
}
