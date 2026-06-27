package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 增值业务产品信息表
 */
@Data
@TableName("ops_value_added_product")
public class OpsValueAddedProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("brand") private String brand;
    @TableField("category") private String category;
    @TableField("model") private String model;
    @TableField("sale_price") private BigDecimal salePrice;
    @TableField("retail_floor_price") private BigDecimal retailFloorPrice;
    @TableField("group_lowest_price") private BigDecimal groupLowestPrice;
    @TableField("image_url") private String imageUrl;
    @TableField("description") private String description;
    @TableField("remark") private String remark;
    @TableField("operator_id") private Long operatorId;
    @TableField("status") private Integer status;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;
}
