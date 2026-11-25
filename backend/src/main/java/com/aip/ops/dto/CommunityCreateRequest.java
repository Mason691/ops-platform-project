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

    /** 详细地址（区县之后的部分，如 XX路XX号），落库时由平台拼接为完整地址 */
    private String detailAddress;

    /** 完整地址（定位反解析或地理编码结果，可选；平台会校验长度） */
    private String fullAddress;

    private BigDecimal longitude;
    private BigDecimal latitude;
    private String coordinateType;
    /** WECHAT / H5 / GEOCODE / MANUAL */
    private String locationSource;
    private BigDecimal locationAccuracy;
    /** 地理编码精度级别（门牌号/道路/区县等） */
    private String geocodeLevel;
    private String province;
    private String city;
    private String districtName;
    private String township;
    private String adcode;
}
