package com.aip.ops.entity;
import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_operator_merchant")
public class OperatorMerchant extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("operator_id") private Long operatorId;
    @TableField("merchant_id") private Long merchantId;
    @TableField("status") private Integer status;
}
