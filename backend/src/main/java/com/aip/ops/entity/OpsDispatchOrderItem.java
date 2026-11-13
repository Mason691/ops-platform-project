package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 派工单商品明细（仅施工类型）。
 *
 * <p>来源为业务订单子表中品类属于燃气灶/抽油烟机/热水器/壁挂炉且数量为正的分录；
 * 退货负数分录不纳入。接单时按 {@link #sourceItemId} 重新校验归属与数量。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_dispatch_order_item")
public class OpsDispatchOrderItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("dispatch_id")
    private Long dispatchId;

    /** 来源分录 ops_business_order_item.id（同商品多经办人行取代表行）。 */
    @TableField("source_item_id")
    private Long sourceItemId;

    @TableField("product_name")
    private String productName;

    @TableField("brand")
    private String brand;

    @TableField("category")
    private String category;

    @TableField("model")
    private String model;

    @TableField("sale_quantity")
    private Integer saleQuantity;

    @TableField("unit")
    private String unit;

    @TableField("sale_price")
    private BigDecimal salePrice;
}
