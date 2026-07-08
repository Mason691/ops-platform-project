-- 网格员管理表
CREATE TABLE IF NOT EXISTS ops_grid_manager (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    corp_id BIGINT NOT NULL DEFAULT 0 COMMENT '组织ID',
    user_id BIGINT NOT NULL COMMENT '关联 ai_platform.sys_user.id',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号（冗余自 sys_user）',
    real_name VARCHAR(100) DEFAULT NULL COMMENT '姓名（冗余自 sys_user）',
    start_time DATE NOT NULL COMMENT '负责开始日期',
    end_time DATE DEFAULT NULL COMMENT '负责结束日期（NULL=长期有效）',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    -- BaseEntity
    update_key VARCHAR(50) DEFAULT NULL,
    unique_id VARCHAR(50) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_user BIGINT DEFAULT NULL,
    modify_user BIGINT DEFAULT NULL,
    create_user_name VARCHAR(100) DEFAULT NULL,
    modify_user_name VARCHAR(100) DEFAULT NULL,
    sort INT DEFAULT 0,
    delete_flag INT DEFAULT 0,
    INDEX idx_corp (corp_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网格员表';

-- 网格员-小区关联表
CREATE TABLE IF NOT EXISTS ops_grid_manager_community (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    grid_manager_id BIGINT NOT NULL COMMENT '关联 ops_grid_manager.id',
    community_id BIGINT NOT NULL COMMENT '关联 ai_platform.sys_community.id',
    INDEX idx_gm (grid_manager_id),
    INDEX idx_community (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网格员-小区关联表';
