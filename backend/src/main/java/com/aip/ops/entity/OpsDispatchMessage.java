package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 派工单公众号消息推送记录：每次（含重发）一行，便于追踪送达与点击。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_dispatch_message")
public class OpsDispatchMessage extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("dispatch_id")
    private Long dispatchId;

    /** DISPATCH_NOTICE / DISPATCH_CANCELLED。 */
    @TableField("scene_code")
    private String sceneCode;

    @TableField("template_id")
    private String templateId;

    @TableField("receiver_user_id")
    private Long receiverUserId;

    @TableField("openid")
    private String openid;

    /** SUCCESS / FAILED / SKIPPED。 */
    @TableField("send_status")
    private String sendStatus;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("send_time")
    private LocalDateTime sendTime;

    /** UNREAD / READ；员工打开工单详情时回写 READ。 */
    @TableField("read_status")
    private String readStatus;

    @TableField("read_time")
    private LocalDateTime readTime;

    @TableField("retry_count")
    private Integer retryCount;
}
