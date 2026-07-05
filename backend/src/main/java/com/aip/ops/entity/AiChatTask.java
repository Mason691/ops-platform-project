package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话异步任务 — sys_ai_chat_task。
 * 支持 Kafka 驱动的异步 AI 对话，替代阻塞式同步调用。
 */
@Data
@TableName("sys_ai_chat_task")
public class AiChatTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("corp_id")
    private Long corpId;

    @TableField("user_id")
    private Long userId;

    @TableField("member_id")
    private Long memberId;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("message")
    private String message;

    @TableField("subsystem")
    private String subsystem;

    @TableField("employee_context")
    private String employeeContext;

    @TableField("status")
    private String status;

    @TableField("phase")
    private String phase;

    @TableField("progress_msg")
    private String progressMsg;

    @TableField("reply")
    private String reply;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("report_token")
    private String reportToken;

    @TableField("partial")
    private Integer partial;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("finish_time")
    private LocalDateTime finishTime;

    @TableField("modify_time")
    private LocalDateTime modifyTime;

    @TableField("sort")
    private Integer sort;

    @TableField("remark")
    private String remark;

    @TableField("delete_flag")
    private Integer deleteFlag;
}
