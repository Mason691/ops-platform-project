package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ops_activity_booking")
public class OpsActivityBooking {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("activity_id") private Long activityId;
    @TableField("user_id") private Long userId;
    @TableField("phone") private String phone;
    @TableField("status") private Integer status;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;
}
