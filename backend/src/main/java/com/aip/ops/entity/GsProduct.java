package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 运营商商品关系映射表。
 * 定位: 关系映射表而非全量同步 — 一条记录代表运营商引入了一个 mall_product 商品。
 */
@Data @TableName("gs_product")
public class GsProduct {
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 引入时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
