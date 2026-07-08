package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("ops_activity_review_cost")
public class OpsActivityReviewCost {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reviewId;
    private String material;
    private BigDecimal amount;
    private String remark;
}
