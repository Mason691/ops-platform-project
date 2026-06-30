package com.aip.ops.service;

/**
 * 微信手机号授权解密服务。
 * 通过 wx.login() 返回的 code 换取 session_key，
 * 再使用 session_key 解密 wx.getPhoneNumber() 返回的加密手机号数据。
 */
public interface WechatPhoneService {

    /**
     * 解密微信加密的手机号。
     *
     * @param code          wx.login() 返回的 code
     * @param encryptedData wx.getPhoneNumber() 返回的 encryptedData
     * @param iv            wx.getPhoneNumber() 返回的 iv
     * @return 解密结果（手机号、openid、unionid），失败返回 null
     */
    WechatPhoneResult decryptPhone(String code, String encryptedData, String iv);

    /** 解密结果 */
    class WechatPhoneResult {
        private final String phone;
        private final String openid;
        private final String unionid;

        public WechatPhoneResult(String phone, String openid, String unionid) {
            this.phone = phone;
            this.openid = openid;
            this.unionid = unionid;
        }
        public String getPhone() { return phone; }
        public String getOpenid() { return openid; }
        public String getUnionid() { return unionid; }
    }
}
