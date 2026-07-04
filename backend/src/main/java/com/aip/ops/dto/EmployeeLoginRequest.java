package com.aip.ops.dto;

import lombok.Data;

@Data
public class EmployeeLoginRequest {
    private String phone;
    private String password;
    private String captchaId;
    private String captchaCode;
}
