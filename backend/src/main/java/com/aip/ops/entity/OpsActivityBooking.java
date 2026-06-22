package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_activity_booking")
public class OpsActivityBooking extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("activity_id") private Long activityId;
    @TableField("user_id") private Long userId;
    @TableField("phone") private String phone;
    @TableField("status") private Integer status;
}
