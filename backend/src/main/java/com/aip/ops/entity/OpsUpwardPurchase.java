package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 向上采购申请记录：财务核对通过后，由部长审核勾选“向上采购”的商品生成。
 *
 * <p>记录按订单 + 商品分组唯一；商品全部明细行ID存 item_ids，用于 SCM 发起采购申请。
 * 状态：PENDING 待发起 / REQUESTED 已发起 / HANDLED 已处理 / INVALID 已失效。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_upward_purchase")
public class OpsUpwardPurchase extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    @TableField("order_no")
    private String orderNo;

    @TableField("receipt_no")
    private String receiptNo;

    /** 商品明细主行ID（ops_business_order_item.id） */
    @TableField("item_id")
    private Long itemId;

    /** 同商品组全部明细行ID，英文逗号分隔；发起采购申请时传给 SCM */
    @TableField("item_ids")
    private String itemIds;

    /** 商品分组键（品类+品牌+型号+单价），与订单详情 productKey 同口径 */
    @TableField("product_key")
    private String productKey;

    @TableField("batch_no")
    private String batchNo;

    @TableField("customer_name")
    private String customerName;

    @TableField("category")
    private String category;

    @TableField("brand")
    private String brand;

    @TableField("model")
    private String model;

    @TableField("quantity")
    private BigDecimal quantity;

    @TableField("sale_price")
    private BigDecimal salePrice;

    @TableField("unit")
    private String unit;

    /** 财务核对时本组织库存快照；实时库存由 H5 重新查询 */
    @TableField("local_stock")
    private BigDecimal localStock;

    /** 其他城市合伙人库存快照 JSON：[{corpId,corpName,stock}] */
    @TableField("other_stock_json")
    private String otherStockJson;

    @TableField("selection_node")
    private String selectionNode;

    @TableField("finance_check_time")
    private LocalDateTime financeCheckTime;

    /** PENDING / REQUESTED / HANDLED / INVALID */
    @TableField("status")
    private String status;

    @TableField("request_bill_no")
    private String requestBillNo;

    @TableField("request_time")
    private LocalDateTime requestTime;

    @TableField("handled_time")
    private LocalDateTime handledTime;

    @TableField("invalid_reason")
    private String invalidReason;
}
