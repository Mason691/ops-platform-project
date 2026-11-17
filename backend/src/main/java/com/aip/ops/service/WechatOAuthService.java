package com.aip.ops.service;

import java.util.Map;

/**
 * 员工微信公众号网页授权（OAuth2.0）。
 * <p>
 * ops 负责用公众号 appId/appSecret 将 code 换成 openid，再委托底座完成
 * 员工账号匹配、绑定与 JWT 签发；底座是员工身份与绑定关系的唯一归属方。
 */
public interface WechatOAuthService {

    /**
     * 生成微信授权跳转地址。
     *
     * @param mode          login=微信登录 / bind=已登录员工绑定微信
     * @param corpId        当前组织（可空）
     * @param currentUserId 绑定模式下当前员工 ID（登录模式可空）
     */
    Map<String, Object> createAuthorizeUrl(String mode, Long corpId, Long currentUserId);

    /** 用回调 code/state 完成微信授权登录，返回与密码登录一致的员工会话结构。 */
    Map<String, Object> loginByCode(String code, String state, String clientIp);

    /** 用回调 code/state 将当前员工账号与微信 openid 绑定。 */
    Map<String, Object> bindByCode(String code, String state, Long currentUserId, String clientIp);

    /** 查询当前员工的微信绑定状态（不抛异常，失败时返回 error 供前端提示）。 */
    Map<String, Object> getBindStatus(Long currentUserId, Long corpId);
}
