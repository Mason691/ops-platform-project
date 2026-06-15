package com.aip.ops.controller;

import com.aip.ops.dto.LoginRequest;
import com.aip.ops.dto.RegisterRequest;
import com.aip.ops.dto.WechatLoginRequest;
import com.aip.ops.service.IUserService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/ops/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IUserService userService;

    /**
     * 手机号+密码注册
     */
    @PostMapping("/register")
    public R<?> register(@RequestBody RegisterRequest req) {
        try {
            return R.ok(userService.register(req));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 手机号+密码登录
     */
    @PostMapping("/login")
    public R<?> login(@RequestBody LoginRequest req) {
        try {
            return R.ok(userService.login(req));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 微信一键登录。
     * 正式环境：前端调用 wx.login + getPhoneNumber 获取 code/encryptedData/iv；
     * 开发环境：前端传 mockPhone 或后端配置默认 mock 手机号。
     */
    @PostMapping("/wechat-login")
    public R<?> wechatLogin(@RequestBody WechatLoginRequest req) {
        try {
            return R.ok(userService.wechatLogin(req));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }
}
