package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务数据导入型号匹配关系表。
 *
 * <p>导入时操作人员对模糊型号（Excel 关键信息 → 产品完整型号）的确认结果落库记忆，
 * 后续导入相同 品牌+品类+型号关键信息 自动套用，免重复确认。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_model_alias")
public class OpsBusinessModelAlias extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID（多租户隔离） */
    @TableField("corp_id")
    private Long corpId;

    /** 品牌（与增值产品信息表一致） */
    @TableField("brand")
    private String brand;

    /** 品类（PRODUCT_CATEGORY 字典 itemKey） */
    @TableField("category")
    private String category;

    /** Excel 填写的型号关键信息（归一化：去空格转小写） */
    @TableField("model_keyword")
    private String modelKeyword;

    /** 匹配到的增值产品 ops_value_added_product.id */
    @TableField("product_id")
    private Long productId;
}
