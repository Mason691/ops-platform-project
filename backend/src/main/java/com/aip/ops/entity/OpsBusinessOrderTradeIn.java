package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务订单以旧换新旧品明细。
 *
 * <p>旧品按销售商品行分组（product_key）挂载，同一产品多经办人共用一份旧品；
 * 自动生成的 SCM 采购订单以这些行逐条生成采购分录。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_order_trade_in")
public class OpsBusinessOrderTradeIn extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    /** 销售商品行分组键：category|brand|model|price。 */
    @TableField("product_key")
    private String productKey;

    /** 该销售商品行的代表明细ID（第一个经办人行）。 */
    @TableField("source_item_id")
    private Long sourceItemId;

    /** SCM 商品ID；手工旧品为空。 */
    @TableField("old_product_id")
    private Long oldProductId;

    /** SCM 商品编码；手工旧品为空。 */
    @TableField("old_material_number")
    private String oldMaterialNumber;

    @TableField("old_product_name")
    private String oldProductName;

    @TableField("brand")
    private String brand;

    @TableField("model")
    private String model;

    @TableField("category")
    private String category;

    @TableField("quantity")
    private Integer quantity;

    @TableField("unit")
    private String unit;

    /** 1=其他旧品手工录入（不在 SCM 商品库）。 */
    @TableField("is_manual")
    private Integer isManual;
}
