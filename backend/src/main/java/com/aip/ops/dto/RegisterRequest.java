package com.aip.ops.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String phone;
    private String password;
    private String confirmPassword;
    private Long corpId;
}
