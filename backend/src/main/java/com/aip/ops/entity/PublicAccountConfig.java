package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_public_account_config")
public class PublicAccountConfig extends BaseEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("app_id") private String appId;
    @TableField("app_secret") private String appSecret;
    @TableField("token") private String token;
    @TableField("aes_key") private String aesKey;
    @TableField("js_domain") private String jsDomain;
    @TableField("name") private String name;
    @TableField("is_default") private Integer isDefault;
    @TableField("status") private Integer status;
}
