-- ============================================================
-- 通用异步导入任务表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_import_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    corp_id BIGINT DEFAULT 0 COMMENT '租户ID',
    user_id BIGINT COMMENT '提交人ID',
    user_name VARCHAR(100) COMMENT '提交人名称',
    task_type VARCHAR(50) NOT NULL COMMENT '任务类型（如 business_data）',
    file_name VARCHAR(255) COMMENT '原始文件名',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    file_path VARCHAR(500) COMMENT '临时文件路径',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/DONE/FAILED',
    total_rows INT DEFAULT 0 COMMENT '总行数',
    success_rows INT DEFAULT 0 COMMENT '成功导入行数',
    fail_rows INT DEFAULT 0 COMMENT '失败行数',
    phase VARCHAR(30) COMMENT '当前阶段: FILE_CHECK/HEADER_CHECK/ROW_CHECK/BUSINESS_CHECK/INSERT',
    error_msg TEXT COMMENT '错误详情',
    progress_msg VARCHAR(500) COMMENT '进度描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME,
    modify_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    sort INT DEFAULT 0,
    remark VARCHAR(500),
    delete_flag INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步导入任务表';
