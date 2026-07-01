-- 通用异步导出任务表
-- 替代 ops_export_task，支持多种导出类型（业务数据/订单等）
-- 执行时间：2026-07-01

CREATE TABLE IF NOT EXISTS sys_export_task (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    corp_id         BIGINT NOT NULL DEFAULT 0              COMMENT '租户ID',
    user_id         BIGINT                                 COMMENT '提交人ID',
    user_name       VARCHAR(64)                            COMMENT '提交人姓名',
    task_type       VARCHAR(50)  NOT NULL                  COMMENT '导出类型: business_data/order/member/...',
    file_name       VARCHAR(200)                           COMMENT '导出文件名',
    file_path       VARCHAR(500)                           COMMENT '服务器文件路径',
    file_size       BIGINT                                 COMMENT '字节数',
    filters         TEXT                                   COMMENT '筛选条件JSON',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED',
    error_msg       VARCHAR(500)                           COMMENT '错误信息',
    progress_msg    VARCHAR(200)                           COMMENT '进度描述',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    finish_time     DATETIME                               COMMENT '完成时间',
    modify_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_user     BIGINT,
    modify_user     BIGINT,
    create_user_name VARCHAR(100),
    modify_user_name VARCHAR(100),
    sort            INT DEFAULT 0,
    remark          VARCHAR(500),
    delete_flag     TINYINT NOT NULL DEFAULT 0,
    INDEX idx_corp_type_status (corp_id, task_type, status),
    INDEX idx_create_time (create_time),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用异步导出任务表';
