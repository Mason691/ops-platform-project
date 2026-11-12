package com.aip.ops.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将底座员工登录响应映射为 ops 前端统一结构。
 * <p>
 * 密码登录与微信授权登录共用，保证两种登录方式返回的字段完全一致。
 */
public final class EmployeeLoginResultMapper {

    private EmployeeLoginResultMapper() {}

    /**
     * @param data          底座响应 data
     * @param fallbackPhone 请求侧手机号（可空，空时取底座返回值）
     * @param jwtExpiration 默认过期秒数
     */
    public static Map<String, Object> map(Map<String, Object> data, String fallbackPhone, long jwtExpiration) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", data.get("token"));
        result.put("employeeId", data.get("userId"));                 // userId → employeeId
        result.put("name", data.getOrDefault("realName", ""));        // realName → name
        Object platformPhone = data.get("phone");
        result.put("phone", fallbackPhone != null && !fallbackPhone.isBlank()
                ? fallbackPhone
                : (platformPhone != null ? platformPhone : ""));
        result.put("role", extractRole(data));                        // first role name
        result.put("corpId", data.get("corpId"));
        result.put("corpName", data.getOrDefault("corpName", ""));           // 组织名称
        result.put("expiresIn", data.getOrDefault("expiresIn", jwtExpiration));
        result.put("companies", data.getOrDefault("companies", List.of()));  // 多组织列表
        result.put("avatar", data.getOrDefault("avatar", ""));               // sys_user 头像
        return result;
    }

    /** 取首个角色名；无角色时回退 employee。 */
    @SuppressWarnings("unchecked")
    private static String extractRole(Map<String, Object> data) {
        List<String> roles = (List<String>) data.get("roles");
        if (roles != null && !roles.isEmpty()) {
            return roles.get(0);
        }
        return "employee";
    }
}
