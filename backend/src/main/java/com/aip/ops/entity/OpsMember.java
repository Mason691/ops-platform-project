package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公众号平台会员表 — 扩展底座 sys_member 的子系统特有字段。
 * 通过 platform_member_id 关联底座统一会员。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_member")
public class OpsMember extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID */
    @TableField("corp_id")
    private Long corpId;

    /** 手机号（登录标识） */
    @TableField("phone")
    private String phone;

    /** BCrypt 加密密码 */
    @TableField("password")
    private String password;

    /** 微信 openid */
    @TableField("openid")
    private String openid;

    /** 微信 unionid（跨客户端免密登录关键字段） */
    @TableField("unionid")
    private String unionid;

    /** 昵称 */
    @TableField("nickname")
    private String nickname;

    /** 头像 URL */
    @TableField("avatar")
    private String avatar;

    /** 积分余额 */
    @TableField("points_balance")
    private Integer pointsBalance;

    /** 底座平台会员ID（关联 sys_member.id） */
    @TableField("platform_member_id")
    private Long platformMemberId;

    /** 账号状态：1=正常，0=禁用 */
    @TableField("status")
    private Integer status;

    /** 最后登录时间 */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;
}
