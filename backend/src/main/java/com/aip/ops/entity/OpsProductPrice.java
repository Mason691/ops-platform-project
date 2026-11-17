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
 * 增值业务产品售价：按供应链商品（scm_bd_material.id）维护标准售价与两级底价。
 *
 * <p>归属运营商组织（corp_id）；城市合伙人读取父运营商配置。
 * 唯一键 (corp_id, product_id)：一个商品一条售价记录；逻辑删除后重新新增时复用该行。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_product_price")
public class OpsProductPrice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** SCM 商品信息ID（scm_bd_material.id） */
    @TableField("product_id")
    private Long productId;

    /** SCM 商品名称（冗余展示） */
    @TableField("product_name")
    private String productName;

    /** 品牌（冗余自 SCM 商品信息，以商品信息为准） */
    @TableField("brand")
    private String brand;

    /** 规格型号（冗余自 SCM 商品信息） */
    @TableField("model")
    private String model;

    /** 品类（PRODUCT_CATEGORY 字典 itemKey） */
    @TableField("category")
    private String category;

    /** 标准销售价 */
    @TableField("sale_price")
    private BigDecimal salePrice;

    /** 零售底价 */
    @TableField("retail_floor_price")
    private BigDecimal retailFloorPrice;

    /** 集团最低价 */
    @TableField("group_lowest_price")
    private BigDecimal groupLowestPrice;

    /** 1=启用 0=禁用 */
    @TableField("status")
    private Integer status;
}
