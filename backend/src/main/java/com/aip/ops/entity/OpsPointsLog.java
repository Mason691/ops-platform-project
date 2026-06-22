package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_points_log")
public class OpsPointsLog extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("change_type") private String changeType;
    @TableField("amount") private Integer amount;
    @TableField("balance_after") private Integer balanceAfter;
    @TableField("order_no") private String orderNo;
    @TableField("remark") private String remark;
}
