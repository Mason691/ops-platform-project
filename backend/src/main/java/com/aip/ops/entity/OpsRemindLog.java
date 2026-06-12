package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ops_remind_log")
public class OpsRemindLog {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("booking_id") private Long bookingId;
    @TableField("remind_type") private String remindType;
    @TableField("status") private Integer status;
    @TableField("send_time") private LocalDateTime sendTime;
    @TableField("create_time") private LocalDateTime createTime;
}
