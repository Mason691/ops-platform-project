package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @TableName("ops_order")
public class GsOrder {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("user_id") private Long userId;
    @TableField("order_no") private String orderNo;
    @TableField("product_id") private Long productId;
    @TableField("product_name") private String productName;
    @TableField("product_image") private String productImage;
    @TableField("points_spent") private Integer pointsSpent;
    @TableField("cash_amount") private BigDecimal cashAmount;
    @TableField("address_id") private Long addressId;
    @TableField("address_snapshot") private String addressSnapshot;
    @TableField("status") private String status;
    @TableField("third_party_order_no") private String thirdPartyOrderNo;
    @TableField("expire_time") private LocalDateTime expireTime;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("modify_time") private LocalDateTime modifyTime;
}
