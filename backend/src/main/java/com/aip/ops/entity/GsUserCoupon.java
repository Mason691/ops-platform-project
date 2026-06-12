package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("ops_user_coupon")
public class GsUserCoupon {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("coupon_id") private Long couponId;
    @TableField("status") private String status;
    @TableField("expire_time") private LocalDateTime expireTime;
    @TableField("used_time") private LocalDateTime usedTime;
    @TableField("create_time") private LocalDateTime createTime;
}
