package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data @TableName("gs_address")
public class GsAddress {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("name") private String name;
    @TableField("phone") private String phone;
    @TableField("province") private String province;
    @TableField("city") private String city;
    @TableField("district") private String district;
    @TableField("detail") private String detail;
    @TableField("is_default") private Integer isDefault;
}
