package com.aip.ops.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String phone;
    private String password;
    private Long corpId;
}
