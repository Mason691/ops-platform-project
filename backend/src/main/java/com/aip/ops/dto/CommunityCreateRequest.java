package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 员工制单新建小区：名称与可选的手机定位结果由前端提供；
 * 组织与所在区县仍由服务端从 JWT / 组织信息派生，不接受前端传入。
 * 坐标统一 GCJ-02，WGS-84 由前端转换后再提交。
 */
@Data
public class CommunityCreateRequest {

    private String name;

    /** 详细地址（高德逆地理编码回填，可手工修改），落库到 sys_community.address */
    private String detailAddress;

    private BigDecimal longitude;
    private BigDecimal latitude;
    private String coordinateType;
    /** WECHAT / H5 / MANUAL */
    private String locationSource;
    private BigDecimal locationAccuracy;
    private String province;
    private String city;
    private String districtName;
    private String township;
    private String adcode;
}
