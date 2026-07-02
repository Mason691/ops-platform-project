-- ============================================================
-- AI 对话异步任务表
-- 支持 Kafka 驱动的异步 AI 对话，替代阻塞式同步调用
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_ai_chat_task (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64)  NOT NULL COMMENT 'UUID 任务标识',
    corp_id         BIGINT       NOT NULL COMMENT '租户ID',
    user_id         BIGINT       DEFAULT NULL COMMENT '用户ID',
    member_id       BIGINT       DEFAULT NULL COMMENT '会员ID（会员端传，员工端不传）',
    conversation_id BIGINT       DEFAULT NULL COMMENT '对话ID（多轮对话）',
    message         TEXT         NOT NULL COMMENT '用户消息原文',
    subsystem       VARCHAR(32)  DEFAULT NULL COMMENT '来源子系统标识',
    employee_context JSON        DEFAULT NULL COMMENT '员工上下文（EmployeeAiController 传递）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING|PROCESSING|WAITING_CONFIRM|COMPLETED|FAILED|TIMEOUT|CANCELLED',
    phase           VARCHAR(20)  DEFAULT NULL
                    COMMENT '当前阶段: INTENT|SPLIT|SUB_TASK|TOOL_CALL|AGGREGATE|LLM',
    progress_msg    VARCHAR(200) DEFAULT NULL COMMENT '用户可读的进度描述',
    reply           TEXT         DEFAULT NULL COMMENT 'AI 最终回复',
    error_msg       VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    partial         TINYINT(1)   DEFAULT 0 COMMENT '是否部分结果（含超时子任务）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time     DATETIME     DEFAULT NULL COMMENT '完成时间',
    modify_time     DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    sort            INT          DEFAULT 0,
    remark          VARCHAR(500) DEFAULT NULL,
    delete_flag     TINYINT(1)   DEFAULT 0,
    UNIQUE INDEX uk_task_id (task_id),
    INDEX idx_corp_user (corp_id, user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话异步任务';
