package com.aip.ops.dto;

import lombok.Data;

/** 微信授权回调 code + state（登录与绑定共用）。 */
@Data
public class EmployeeWechatCodeRequest {

    private String code;

    private String state;
}
