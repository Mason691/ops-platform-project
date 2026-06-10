package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("biz_operator_merchant")
public class OperatorMerchant {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("operator_id") private Long operatorId;
    @TableField("merchant_id") private Long merchantId;
    @TableField("status") private Integer status;
    @TableField("create_time") private LocalDateTime createTime;
}
