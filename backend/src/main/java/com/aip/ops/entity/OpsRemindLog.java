package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_remind_log")
public class OpsRemindLog extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("booking_id") private Long bookingId;
    @TableField("live_room_id") private Long liveRoomId;
    @TableField("remind_type") private String remindType;
    @TableField("status") private Integer status;
    @TableField("send_time") private LocalDateTime sendTime;
}
