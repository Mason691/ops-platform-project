package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Employee login account — separate from {@link OpsMember} (member accounts).
 * Created by admin; employees authenticate via phone + password in WeChat browser.
 *
 * @deprecated since 2026-07-04 — employee authentication is now delegated to the base platform's
 *             {@code sys_user} table via {@code POST /api/auth/employee/login}.
 *             This table is retained for historical data only.
 *             New employee accounts should be created as {@code sys_user} records on the platform.
 */
@Deprecated
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_employee")
public class OpsEmployee extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID */
    @TableField("corp_id")
    private Long corpId;

    /** 员工姓名 */
    @TableField("name")
    private String name;

    /** 手机号（登录账号） */
    @TableField("phone")
    private String phone;

    /** BCrypt 加密密码 */
    @TableField("password")
    private String password;

    /** 角色: employee/manager/admin */
    @TableField("role")
    private String role;

    /** 状态: 1=正常 0=禁用 */
    @TableField("status")
    private Integer status;

    /** 连续登录失败次数 */
    @TableField("login_fail_count")
    private Integer loginFailCount;

    /** 锁定到期时间（null 表示未锁定） */
    @TableField("login_locked_until")
    private LocalDateTime loginLockedUntil;

    /** 最后登录时间 */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;
}
