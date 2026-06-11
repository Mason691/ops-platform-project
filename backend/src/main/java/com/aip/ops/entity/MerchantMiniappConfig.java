package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 销售商小程序跳转配置。
 * 一个销售商（merchant）仅一条配置，存储其微信小程序 AppID 和路径模板。
 */
@Data
@TableName("biz_merchant_miniapp_config")
public class MerchantMiniappConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

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

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
