package com.aip.ops.service;

import com.aip.ops.dto.LoginRequest;
import com.aip.ops.dto.RegisterRequest;
import com.aip.ops.dto.WechatLoginRequest;
import java.util.Map;

public interface IUserService {
    Map<String, Object> register(RegisterRequest req);
    Map<String, Object> login(LoginRequest req);
    Map<String, Object> wechatLogin(WechatLoginRequest req);
}
