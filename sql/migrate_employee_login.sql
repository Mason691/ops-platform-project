-- Employee login account table
-- Employees authenticate via phone + password (separate from member login)
-- Admin creates accounts; employees log in via WeChat browser

CREATE TABLE IF NOT EXISTS `ops_employee` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `corp_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `name` VARCHAR(64) NOT NULL COMMENT '员工姓名',
  `phone` VARCHAR(20) NOT NULL COMMENT '手机号（登录账号）',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `role` VARCHAR(32) DEFAULT 'employee' COMMENT '角色: employee/manager/admin',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=正常 0=禁用',
  `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `login_locked_until` DATETIME NULL COMMENT '锁定到期时间',
  `last_login_time` DATETIME NULL COMMENT '最后登录时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `create_user` BIGINT NULL COMMENT '创建人',
  `modify_user` BIGINT NULL COMMENT '修改人',
  `delete_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone_corp` (`phone`, `corp_id`),
  KEY `idx_corp_id` (`corp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工登录账号表';
