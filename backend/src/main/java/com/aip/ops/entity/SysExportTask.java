package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通用异步导出任务 — sys_export_task。
 * 支持多种导出类型（业务数据/订单/会员等），由 task_type 路由到对应 ExportHandler。
 */
@Data
@TableName("sys_export_task")
public class SysExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("user_id")
    private Long userId;

    @TableField("user_name")
    private String userName;

    @TableField("task_type")
    private String taskType;

    @TableField("file_name")
    private String fileName;

    @TableField("file_path")
    private String filePath;

    @TableField("file_size")
    private Long fileSize;

    @TableField("filters")
    private String filters;

    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("progress_msg")
    private String progressMsg;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("finish_time")
    private LocalDateTime finishTime;

    @TableField("modify_time")
    private LocalDateTime modifyTime;

    @TableField("create_user")
    private Long createUser;

    @TableField("modify_user")
    private Long modifyUser;

    @TableField("create_user_name")
    private String createUserName;

    @TableField("modify_user_name")
    private String modifyUserName;

    @TableField("sort")
    private Integer sort;

    @TableField("remark")
    private String remark;

    @TableField("delete_flag")
    private Integer deleteFlag;
}
