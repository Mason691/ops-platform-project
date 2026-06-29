package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 增值业务产品信息表。
 *
 * <p>status 字段取值映射系统字典 ENABLE_DISABLE：1=启用 0=禁用</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_value_added_product")
public class OpsValueAddedProduct extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    /** 品牌 */
    @TableField("brand")
    private String brand;

    /** 品类（sys_dict_item.item_key, type=PRODUCT_CATEGORY） */
    @TableField("category")
    private String category;

    /** 型号 */
    @TableField("model")
    private String model;

    /** 销售价 */
    @TableField("sale_price")
    private BigDecimal salePrice;

    /** 零售底价 */
    @TableField("retail_floor_price")
    private BigDecimal retailFloorPrice;

    /** 集团最低价 */
    @TableField("group_lowest_price")
    private BigDecimal groupLowestPrice;

    /** 图片 URL */
    @TableField("image_url")
    private String imageUrl;

    /** 描述 */
    @TableField("description")
    private String description;

    /**
     * @deprecated 使用 {@link #corpId} 替代；保留字段仅用于向后兼容
     */
    @Deprecated
    @TableField("operator_id")
    private Long operatorId;

    /** 状态：1=启用 0=禁用（ENABLE_DISABLE 字典） */
    @TableField("status")
    private Integer status;
}
