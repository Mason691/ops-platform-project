package com.aip.ops.entity;
import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运营商商品关系映射表。
 * 定位: 关系映射表而非全量同步 — 一条记录代表运营商引入了一个 mall_product 商品。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_product")
public class GsProduct extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 关联 mall_product.id */
    @TableField("mall_product_id")
    private Long mallProductId;

    /** 运营商ID（引入该商品的组织） */
    @TableField("operator_id")
    private Long operatorId;

    /** 上下架状态 1=上架 0=下架 */
    @TableField("shelf_status")
    private Integer shelfStatus;

    /** 运营商自定义分类ID */
    @TableField("category_id")
    private Long categoryId;

    /** 积分配额（运营配置） */
    @TableField("points")
    private Integer points;

    /** 兑换金额（运营配置） */
    @TableField("exchange_amount")
    private java.math.BigDecimal exchangeAmount;
}
