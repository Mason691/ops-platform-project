package com.aip.ops.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    /** 微信加密数据（正式环境） */
    private String encryptedData;
    /** 加密算法初始向量（正式环境） */
    private String iv;
    /** 微信登录 code（用于换取 session_key） */
    private String code;

    /** 开发模式：URL 参数或前端传入的 mock 手机号 */
    private String mockPhone;

    private String password;
    private String confirmPassword;
    private String nickname;
    private Long corpId;
}
