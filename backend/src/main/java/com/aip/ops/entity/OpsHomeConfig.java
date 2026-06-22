package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_home_config")
public class OpsHomeConfig extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("config_type")
    private String configType;

    @TableField("title")
    private String title;

    @TableField("image_url")
    private String imageUrl;

    @TableField("jump_path")
    private String jumpPath;

    @TableField("target_id")
    private Long targetId;

    @TableField("sort")
    private Integer sort;

    @TableField("status")
    private Integer status;

    @TableField("operator_id")
    private Long operatorId;
}
