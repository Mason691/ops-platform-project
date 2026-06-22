package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_click_log")
public class OpsClickLog extends BaseEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("target_type") private String targetType;
    @TableField("target_id") private Long targetId;
    /** createTime 由 BaseEntity 继承 */
}

