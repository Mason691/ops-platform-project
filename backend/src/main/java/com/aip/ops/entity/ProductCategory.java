package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data @TableName("biz_product_category")
public class ProductCategory {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("name") private String name;
    @TableField("parent_id") private Long parentId;
    @TableField("sort_order") private Integer sortOrder;
    @TableField("status") private Integer status;
}
