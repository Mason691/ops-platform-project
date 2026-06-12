package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("ops_click_log")
public class GsClickLog {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("target_type") private String targetType;
    @TableField("target_id") private Long targetId;
    @TableField("create_time") private LocalDateTime createTime;
}
