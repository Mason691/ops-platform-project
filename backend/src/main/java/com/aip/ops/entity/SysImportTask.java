package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通用异步导入任务 — sys_import_task。
 * 支持多种导入类型（业务数据等），由 task_type 路由到对应 ImportHandler。
 */
@Data
@TableName("sys_import_task")
public class SysImportTask {

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

    @TableField("file_size")
    private Long fileSize;

    @TableField("file_path")
    private String filePath;

    @TableField("status")
    private String status;

    @TableField("total_rows")
    private Integer totalRows;

    @TableField("success_rows")
    private Integer successRows;

    @TableField("fail_rows")
    private Integer failRows;

    @TableField("phase")
    private String phase;

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
