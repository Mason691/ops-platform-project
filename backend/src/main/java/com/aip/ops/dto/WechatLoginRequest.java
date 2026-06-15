package com.aip.ops.dto;

import lombok.Data;

/**
 * 微信一键登录请求。
 * 正式环境通过 wx.login + getPhoneNumber 获取 code/encryptedData/iv；
 * 开发环境可通过 mockPhone 直接指定手机号。
 */
@Data
public class WechatLoginRequest {
    /** 微信加密数据（正式环境） */
    private String encryptedData;
    /** 加密算法初始向量（正式环境） */
    private String iv;
    /** 微信登录 code（用于换取 session_key） */
    private String code;
    /** 开发模式：前端传入或 URL 参数指定的 mock 手机号 */
    private String mockPhone;
    /** 微信昵称（可选，首次注册时使用） */
    private String nickname;
    /** 组织 ID（可选，默认取请求方 corpId） */
    private Long corpId;
}
