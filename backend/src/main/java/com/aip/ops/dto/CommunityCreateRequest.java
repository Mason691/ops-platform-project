package com.aip.ops.dto;

import lombok.Data;

/**
 * 员工制单新建小区：仅名称由前端提供；
 * 组织与所在区县由服务端从 JWT / 组织信息派生，不接受前端传入。
 */
@Data
public class CommunityCreateRequest {

    private String name;
}
