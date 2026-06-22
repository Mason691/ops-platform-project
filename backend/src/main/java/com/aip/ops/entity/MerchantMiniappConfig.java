package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 销售商小程序跳转配置。
 * 一个销售商（merchant）仅一条配置，存储其微信小程序 AppID 和路径模板。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_merchant_miniapp_config")
public class MerchantMiniappConfig extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("merchant_id")
    private Long merchantId;

    @TableField("app_id")
    private String appId;

    @TableField("path_template")
    private String pathTemplate;

    @TableField("env_version")
    private String envVersion;

    @TableField("remark")
    private String remark;

    // Explicit getters — workaround for Lombok annotation processor issue
    public String getPathTemplate() { return pathTemplate; }
    public String getEnvVersion() { return envVersion; }
}
