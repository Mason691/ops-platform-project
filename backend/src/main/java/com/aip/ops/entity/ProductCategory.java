package com.aip.ops.entity;
import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_product_category")
public class ProductCategory extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("name") private String name;
    @TableField("parent_id") private Long parentId;
    @TableField("sort_order") private Integer sortOrder;
    @TableField("status") private Integer status;
}
