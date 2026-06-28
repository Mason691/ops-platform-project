package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ops_business_data")
public class OpsBusinessData {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("batch_no") private String batchNo;
    @TableField("company") private String company;
    @TableField("customer") private String customer;
    @TableField("brand") private String brand;
    @TableField("category") private String category;
    @TableField("model") private String model;
    @TableField("sale_price") private BigDecimal salePrice;
    @TableField("sale_date") private String saleDate;
    @TableField("receipt_date") private String receiptDate;
    @TableField("receipt_no") private String receiptNo;
    @TableField("agent") private String agent;
    @TableField("retail_floor_price") private BigDecimal retailFloorPrice;
    @TableField("group_lowest_price") private BigDecimal groupLowestPrice;
    @TableField("commission_rate") private BigDecimal commissionRate;
    @TableField("commission_amount") private BigDecimal commissionAmount;
    @TableField("remark") private String remark;
    @TableField("operator_id") private Long operatorId;
    @TableField("create_time") private LocalDateTime createTime;
}
